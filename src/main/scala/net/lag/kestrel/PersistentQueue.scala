/*
 * Copyright 2009 Twitter, Inc.
 * Copyright 2009 Robey Pointer <robeypointer@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.lag.kestrel

import java.io._
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.channels.FileChannel
import java.util.concurrent.CountDownLatch
import scala.actors.{Actor, TIMEOUT}
import scala.collection.mutable
import com.twitter.xrayspecs.Time
import net.lag.configgy.{Config, Configgy, ConfigMap}
import net.lag.logging.Logger


// a config value that's backed by a global setting but may be locally overridden
class OverlaySetting[T](base: => T) {
  @volatile private var local: Option[T] = None

  def set(value: Option[T]) = local = value

  def apply() = local.getOrElse(base)
}


class PersistentQueue(persistencePath: String, val name: String,
                      val config: ConfigMap) {

  private case class Waiter(actor: Actor)
  private case object ItemArrived


  private val log = Logger.get

  // current size of all data in the queue:
  private var queueSize: Long = 0

  // # of items EVER added to the queue:
  private var _totalItems: Long = 0

  // # of items that were expired by the time they were read:
  private var _totalExpired: Long = 0

  // age (in milliseconds) of the last item read from the queue:
  private var _currentAge: Long = 0

  // # of items thot were discarded because the queue was full:
  private var _totalDiscarded: Long = 0

  // # of items in the queue (including those not in memory)
  private var queueLength: Long = 0

  private var queue = new mutable.Queue[QItem] {
    // scala's Queue doesn't (yet?) have a way to put back.
    def unget(item: QItem) = prependElem(item)
  }
  private var _memoryBytes: Long = 0

  private var closed = false
  private var paused = false

  def overlay[T](base: => T) = new OverlaySetting(base)

  // attempting to add an item after the queue reaches this size (in items) will fail.
  val maxItems = overlay(PersistentQueue.maxItems)

  // attempting to add an item after the queue reaches this size (in bytes) will fail.
  val maxSize = overlay(PersistentQueue.maxSize)

  // attempting to add an item larger than this size (in bytes) will fail.
  val maxItemSize = overlay(PersistentQueue.maxItemSize)

  // maximum expiration time for this queue (seconds).
  val maxAge = overlay(PersistentQueue.maxAge)

  // maximum journal size before the journal should be rotated.
  val maxJournalSize = overlay(PersistentQueue.maxJournalSize)

  // maximum size of a queue before it drops into read-behind mode.
  val maxMemorySize = overlay(PersistentQueue.maxMemorySize)

  // maximum overflow (multiplier) of a journal file before we re-create it.
  val maxJournalOverflow = overlay(PersistentQueue.maxJournalOverflow)

  // absolute maximum size of a journal file until we rebuild it, no matter what.
  val maxJournalSizeAbsolute = overlay(PersistentQueue.maxJournalSizeAbsolute)

  // whether to drop older items (instead of newer) when the queue is full
  val discardOldWhenFull = overlay(PersistentQueue.discardOldWhenFull)

  // whether to keep a journal file at all
  val keepJournal = overlay(PersistentQueue.keepJournal)

  // whether to sync the journal after each transaction
  val syncJournal = overlay(PersistentQueue.syncJournal)

  // (optional) move expired items over to this queue
  val expiredQueue = overlay(PersistentQueue.expiredQueue)

  // clients waiting on an item in this queue
  private val waiters = new mutable.ArrayBuffer[Waiter]

  private var journal = new Journal(new File(persistencePath, name).getCanonicalPath, syncJournal())

  // track tentative removals
  private var xidCounter: Int = 0
  private val openTransactions = new mutable.HashMap[Int, QItem]
  def openTransactionCount = openTransactions.size
  def openTransactionIds = openTransactions.keys.toList.sort(_ - _ > 0)

  def length: Long = synchronized { queueLength }
  def totalItems: Long = synchronized { _totalItems }
  def bytes: Long = synchronized { queueSize }
  def journalSize: Long = synchronized { journal.size }
  def totalExpired: Long = synchronized { _totalExpired }
  def currentAge: Long = synchronized { if (queueSize == 0) 0 else _currentAge }
  def waiterCount: Long = synchronized { waiters.size }
  def totalDiscarded: Long = synchronized { _totalDiscarded }
  def isClosed: Boolean = synchronized { closed || paused }

  // mostly for unit tests.
  def memoryLength: Long = synchronized { queue.size }
  def memoryBytes: Long = synchronized { _memoryBytes }
  def inReadBehind = synchronized { journal.inReadBehind }


  config.subscribe { c => configure(c.getOrElse(new Config)) }
  configure(config)

  def configure(config: ConfigMap) = synchronized {
    maxItems set config.getInt("max_items")
    maxSize set config.getLong("max_size")
    maxItemSize set config.getLong("max_item_size")
    maxAge set config.getInt("max_age")
    maxJournalSize set config.getLong("max_journal_size")
    maxMemorySize set config.getLong("max_memory_size")
    maxJournalOverflow set config.getInt("max_journal_overflow")
    maxJournalSizeAbsolute set config.getLong("max_journal_size_absolute")
    discardOldWhenFull set config.getBool("discard_old_when_full")
    keepJournal set config.getBool("journal")
    syncJournal set config.getBool("sync_journal")
    expiredQueue set config.getString("move_expired_to").map { qname => Kestrel.queues.queue(qname) }
    log.info("Configuring queue %s: journal=%s, max_items=%d, max_size=%d, max_age=%d, max_journal_size=%d, " +
             "max_memory_size=%d, max_journal_overflow=%d, max_journal_size_absolute=%d, " +
             "discard_old_when_full=%s, sync_journal=%s",
             name, keepJournal(), maxItems(), maxSize(), maxAge(), maxJournalSize(), maxMemorySize(),
             maxJournalOverflow(), maxJournalSizeAbsolute(), discardOldWhenFull(), syncJournal())
    if (!keepJournal()) journal.erase()
  }

  def dumpConfig(): Array[String] = synchronized {
    Array(
      "max_items=" + maxItems(),
      "max_size=" + maxSize(),
      "max_age=" + maxAge(),
      "max_journal_size=" + maxJournalSize(),
      "max_memory_size=" + maxMemorySize(),
      "max_journal_overflow=" + maxJournalOverflow(),
      "max_journal_size_absolute=" + maxJournalSizeAbsolute(),
      "discard_old_when_full=" + discardOldWhenFull(),
      "journal=" + keepJournal(),
      "sync_journal=" + syncJournal(),
      "move_expired_to" + expiredQueue().map { _.name }.getOrElse("(none)")
    )
  }

  def dumpStats(): Array[(String, String)] = synchronized {
    Array(
      ("items", length.toString),
      ("bytes", bytes.toString),
      ("total_items", totalItems.toString),
      ("logsize", journalSize.toString),
      ("expired_items", totalExpired.toString),
      ("mem_items", memoryLength.toString),
      ("mem_bytes", memoryBytes.toString),
      ("age", currentAge.toString),
      ("discarded", totalDiscarded.toString),
      ("waiters", waiterCount.toString),
      ("open_transactions", openTransactionCount.toString)
    )
  }

  private final def adjustExpiry(startingTime: Long, expiry: Long): Long = {
    if (maxAge() > 0) {
      val maxExpiry = startingTime + maxAge()
      if (expiry > 0) (expiry min maxExpiry) else maxExpiry
    } else {
      expiry
    }
  }

  /**
   * Add a value to the end of the queue, transactionally.
   */
  def add(value: Array[Byte], expiry: Long): Boolean = synchronized {
    if (closed || value.size > maxItemSize()) return false
    while (queueLength >= maxItems() || queueSize >= maxSize()) {
      if (!discardOldWhenFull()) return false
      _remove(false)
      _totalDiscarded += 1
      if (keepJournal()) journal.remove()
    }

    val now = Time.now.inMilliseconds
    val item = QItem(now, adjustExpiry(now, expiry), value, 0)
    if (keepJournal() && !journal.inReadBehind) {
      if (journal.size > maxJournalSize() * maxJournalOverflow() && queueSize < maxJournalSize()) {
        // force re-creation of the journal.
        log.info("Rolling journal file for '%s' (qsize=%d)", name, queueSize)
        journal.roll(xidCounter, openTransactionIds map { openTransactions(_) }, queue)
      }
      if (queueSize >= maxMemorySize()) {
        log.info("Dropping to read-behind for queue '%s' (%d bytes)", name, queueSize)
        journal.startReadBehind
      }
    }
    _add(item)
    if (keepJournal()) journal.add(item)
    if (waiters.size > 0) {
      waiters.remove(0).actor ! ItemArrived
    }
    true
  }

  def add(value: Array[Byte]): Boolean = add(value, 0)

  /**
   * Peek at the head item in the queue, if there is one.
   */
  def peek(): Option[QItem] = {
    synchronized {
      if (closed || paused || queueLength == 0) {
        None
      } else {
        _peek()
      }
    }
  }

  /**
   * Remove and return an item from the queue, if there is one.
   *
   * @param transaction true if this should be considered the first part
   *     of a transaction, to be committed or rolled back (put back at the
   *     head of the queue)
   */
  def remove(transaction: Boolean): Option[QItem] = {
    synchronized {
      if (closed || paused || queueLength == 0) {
        None
      } else {
        val item = _remove(transaction)
        if (keepJournal()) {
          if (transaction) journal.removeTentative() else journal.remove()

          if ((queueLength == 0) && (journal.size >= maxJournalSize())) {
            log.info("Rolling journal file for '%s'", name)
            journal.roll(xidCounter, openTransactionIds map { openTransactions(_) }, Nil)
          }
        }
        item
      }
    }
  }

  /**
   * Remove and return an item from the queue, if there is one.
   */
  def remove(): Option[QItem] = remove(false)

  def operateReact(op: => Option[QItem], timeoutAbsolute: Long)(f: Option[QItem] => Unit): Unit = {
    operateOrWait(op, timeoutAbsolute) match {
      case (item, None) =>
        f(item)
      case (None, Some(w)) =>
        Actor.self.reactWithin((timeoutAbsolute - Time.now.inMilliseconds) max 0) {
          case ItemArrived => operateReact(op, timeoutAbsolute)(f)
          case TIMEOUT => synchronized {
            waiters -= w
            // race: someone could have done an add() between the timeout and grabbing the lock.
            Actor.self.reactWithin(0) {
              case ItemArrived => f(op)
              case TIMEOUT => f(op)
            }
          }
        }
      case _ => throw new RuntimeException()
    }
  }

  def operateReceive(op: => Option[QItem], timeoutAbsolute: Long): Option[QItem] = {
    operateOrWait(op, timeoutAbsolute) match {
      case (item, None) =>
        item
      case (None, Some(w)) =>
        val gotSomething = Actor.self.receiveWithin((timeoutAbsolute - Time.now.inMilliseconds) max 0) {
          case ItemArrived => true
          case TIMEOUT => false
        }
        if (gotSomething) {
          operateReceive(op, timeoutAbsolute)
        } else {
          synchronized { waiters -= w }
          // race: someone could have done an add() between the timeout and grabbing the lock.
          Actor.self.receiveWithin(0) {
            case ItemArrived =>
            case TIMEOUT =>
          }
          op
        }
      case _ => throw new RuntimeException()
    }
  }

  def removeReact(timeoutAbsolute: Long, transaction: Boolean)(f: Option[QItem] => Unit): Unit = {
    operateReact(remove(transaction), timeoutAbsolute)(f)
  }

  def removeReceive(timeoutAbsolute: Long, transaction: Boolean): Option[QItem] = {
    operateReceive(remove(transaction), timeoutAbsolute)
  }

  def peekReact(timeoutAbsolute: Long)(f: Option[QItem] => Unit): Unit = {
    operateReact(peek, timeoutAbsolute)(f)
  }

  def peekReceive(timeoutAbsolute: Long): Option[QItem] = {
    operateReceive(peek, timeoutAbsolute)
  }

  /**
   * Perform an operation on the next item from the queue, if there is one.
   * If the queue is closed, returns immediately. Otherwise, if a timeout is passed in, the
   * current actor is added to the wait-list, and will receive `ItemArrived` when an item is
   * available (or the queue is closed).
   */
  private def operateOrWait(op: => Option[QItem], timeoutAbsolute: Long): (Option[QItem], Option[Waiter]) = synchronized {
    val item = op
    if (!item.isDefined && !closed && !paused && timeoutAbsolute > 0) {
      val w = Waiter(Actor.self)
      waiters += w
      (None, Some(w))
    } else {
      (item, None)
    }
  }

  /**
   * Return a transactionally-removed item to the queue. This is a rolled-
   * back transaction.
   */
  def unremove(xid: Int): Unit = {
    synchronized {
      if (!closed) {
        if (keepJournal()) journal.unremove(xid)
        _unremove(xid)
        if (waiters.size > 0) {
          waiters.remove(0).actor ! ItemArrived
        }
      }
    }
  }

  def confirmRemove(xid: Int): Unit = {
    synchronized {
      if (!closed) {
        if (keepJournal()) journal.confirmRemove(xid)
        openTransactions.removeKey(xid)
      }
    }
  }

  def flush(): Unit = {
    while (remove(false).isDefined) { }
  }

  /**
   * Close the queue's journal file. Not safe to call on an active queue.
   */
  def close(): Unit = synchronized {
    closed = true
    if (keepJournal()) journal.close()
    for (w <- waiters) {
      w.actor ! ItemArrived
    }
    waiters.clear()
  }

  def pauseReads(): Unit = synchronized {
    paused = true
    for (w <- waiters) {
      w.actor ! ItemArrived
    }
    waiters.clear()
  }

  def resumeReads(): Unit = synchronized {
    paused = false
  }

  def setup(): Unit = synchronized {
    queueSize = 0
    replayJournal
  }

  def destroyJournal(): Unit = synchronized {
    if (keepJournal()) journal.erase()
  }

  private final def nextXid(): Int = {
    do {
      xidCounter += 1
    } while (openTransactions contains xidCounter)
    xidCounter
  }

  private final def fillReadBehind(): Unit = {
    // if we're in read-behind mode, scan forward in the journal to keep memory as full as
    // possible. this amortizes the disk overhead across all reads.
    while (keepJournal() && journal.inReadBehind && _memoryBytes < maxMemorySize()) {
      journal.fillReadBehind { item =>
        queue += item
        _memoryBytes += item.data.length
      }
      if (!journal.inReadBehind) {
        log.info("Coming out of read-behind for queue '%s'", name)
      }
    }
  }

  def replayJournal(): Unit = {
    if (!keepJournal()) return

    log.info("Replaying transaction journal for '%s'", name)
    xidCounter = 0

    journal.replay(name) {
      case JournalItem.Add(item) =>
        _add(item)
        // when processing the journal, this has to happen after:
        if (!journal.inReadBehind && queueSize >= maxMemorySize()) {
          log.info("Dropping to read-behind for queue '%s' (%d bytes)", name, queueSize)
          journal.startReadBehind
        }
      case JournalItem.Remove => _remove(false)
      case JournalItem.RemoveTentative => _remove(true)
      case JournalItem.SavedXid(xid) => xidCounter = xid
      case JournalItem.Unremove(xid) => _unremove(xid)
      case JournalItem.ConfirmRemove(xid) => openTransactions.removeKey(xid)
      case x => log.error("Unexpected item in journal: %s", x)
    }

    log.info("Finished transaction journal for '%s' (%d items, %d bytes)", name, queueLength,
             journal.size)
    journal.open

    // now, any unfinished transactions must be backed out.
    for (xid <- openTransactionIds) {
      journal.unremove(xid)
      _unremove(xid)
    }
  }


  //  -----  internal implementations

  private def _add(item: QItem): Unit = {
    discardExpired
    if (!journal.inReadBehind) {
      queue += item
      _memoryBytes += item.data.length
    }
    _totalItems += 1
    queueSize += item.data.length
    queueLength += 1
  }

  private def _peek(): Option[QItem] = {
    discardExpired
    if (queue.isEmpty) None else Some(queue.front)
  }

  private def _remove(transaction: Boolean): Option[QItem] = {
    discardExpired()
    if (queue.isEmpty) return None

    val now = Time.now.inMilliseconds
    val item = queue.dequeue
    val len = item.data.length
    queueSize -= len
    _memoryBytes -= len
    queueLength -= 1
    val xid = if (transaction) nextXid else 0

    fillReadBehind
    _currentAge = now - item.addTime
    if (transaction) {
      item.xid = xid
      openTransactions(xid) = item
    }
    Some(item)
  }

  final def discardExpired(): Int = {
    if (queue.isEmpty || journal.isReplaying) {
      0
    } else {
      val realExpiry = adjustExpiry(queue.front.addTime, queue.front.expiry)
      if ((realExpiry != 0) && (realExpiry < Time.now.inMilliseconds)) {
        _totalExpired += 1
        val item = queue.dequeue
        val len = item.data.length
        queueSize -= len
        _memoryBytes -= len
        queueLength -= 1
        fillReadBehind
        if (keepJournal()) journal.remove()
        expiredQueue().map { _.add(item.data, 0) }
        1 + discardExpired()
      } else {
        0
      }
    }
  }

  private def _unremove(xid: Int) = {
    openTransactions.removeKey(xid) map { item =>
      queueLength += 1
      queueSize += item.data.length
      queue unget item
      _memoryBytes += item.data.length
    }
  }
}


object PersistentQueue {
  @volatile var maxItems: Int = Math.MAX_INT
  @volatile var maxSize: Long = Math.MAX_LONG
  @volatile var maxItemSize: Long = Math.MAX_LONG
  @volatile var maxAge: Int = 0
  @volatile var maxJournalSize: Long = 16 * 1024 * 1024
  @volatile var maxMemorySize: Long = 128 * 1024 * 1024
  @volatile var maxJournalOverflow: Int = 10
  @volatile var maxJournalSizeAbsolute: Long = Math.MAX_LONG
  @volatile var discardOldWhenFull: Boolean = false
  @volatile var keepJournal: Boolean = true
  @volatile var syncJournal: Boolean = false
  @volatile var expiredQueue: Option[PersistentQueue] = None
}
