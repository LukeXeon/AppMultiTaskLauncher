package open.source.multitask

import android.os.Looper
import androidx.core.os.HandlerCompat
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

internal class ExclusiveMainThreadExecutor : AbstractExecutorService(),
    ScheduledExecutorService {

    companion object {
        private val sequencer = AtomicLong()

        private fun now(): Long {
            return System.nanoTime()
        }

        private fun triggerTime(queue: DelayQueue<*>, delay: Long): Long {
            return now() + if (delay < Long.MAX_VALUE shr 1) delay else overflowFree(
                queue,
                delay
            )
        }

        private fun triggerTime(queue: DelayQueue<*>, delay: Long, unit: TimeUnit): Long {
            return triggerTime(queue, unit.toNanos(if (delay < 0) 0 else delay))
        }

        private fun overflowFree(queue: DelayQueue<*>, delay: Long): Long {
            var d = delay
            val head = queue.peek()
            if (head != null) {
                val headDelay = head.getDelay(TimeUnit.NANOSECONDS)
                if (headDelay < 0 && d - headDelay < 0) {
                    d = Long.MAX_VALUE + headDelay
                }
            }
            return d
        }
    }

    private val mainThread = HandlerCompat.createAsync(Looper.getMainLooper())
    private val queue = DelayQueue<ScheduledFutureTask<*>>()
    private val runner = object : Runnable {
        override fun run() {
            if (countDownLatch.count > 0) {
                mainThread.postAtFrontOfQueue(this)
                queue.poll()?.run()
            } else {
                mainThread.removeCallbacks(this)
            }
        }
    }
    private val countDownLatch = CountDownLatch(1)

    init {
        mainThread.postAtFrontOfQueue(runner)
    }

    internal inner class ScheduledFutureTask<V> : FutureTask<V>, RunnableScheduledFuture<V> {
        private val sequenceNumber = sequencer.getAndIncrement()
        private var time: Long
        private val period: Long


        constructor(callable: Callable<V>, ns: Long) : super(callable) {
            time = ns
            period = 0
        }

        constructor(runnable: Runnable, result: V, ns: Long, period: Long = 0) : super(
            runnable,
            result
        ) {
            this.time = ns
            this.period = period
        }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            val r = super.cancel(mayInterruptIfRunning)
            queue.remove(this)
            return r
        }

        override fun getDelay(unit: TimeUnit): Long {
            return unit.convert(time - now(), TimeUnit.NANOSECONDS)
        }

        override fun compareTo(other: Delayed): Int {
            if (other === this) {
                return 0
            }
            if (other is ScheduledFutureTask<*>) {
                val diff: Long = time - other.time
                if (diff < 0) {
                    return -1
                }
                if (diff > 0) {
                    return 1
                }
                return if (sequenceNumber < other.sequenceNumber) {
                    -1
                } else 1
            }
            val diff = getDelay(TimeUnit.NANOSECONDS) - other.getDelay(TimeUnit.NANOSECONDS)
            return if (diff < 0) -1 else if (diff > 0) 1 else 0
        }

        override fun isPeriodic(): Boolean {
            return period != 0L
        }

        /**
         * Sets the next time to run for a periodic task.
         */
        private fun setNextRunTime() {
            val p = period
            if (p > 0) {
                time += p
            } else {
                time = triggerTime(queue, -p)
            }
        }

        override fun run() {
            val periodic = isPeriodic
            if (!periodic) {
                super.run()
            } else if (runAndReset()) {
                setNextRunTime()
                queue.offer(this)
            }
        }
    }

    override fun execute(command: Runnable) {
        if (!isShutdown) {
            queue.add(ScheduledFutureTask(command, Unit, triggerTime(queue, 0)))
        } else {
            throw RejectedExecutionException("Already shutdown")
        }
    }

    override fun shutdown() {
        countDownLatch.countDown()
        mainThread.removeCallbacks(runner)
        queue.clear()
    }

    override fun shutdownNow(): List<Runnable> {
        countDownLatch.countDown()
        mainThread.removeCallbacks(runner)
        val list = queue.toList()
        queue.clear()
        return list
    }

    override fun isShutdown(): Boolean {
        return countDownLatch.count <= 0
    }

    override fun isTerminated(): Boolean {
        return isShutdown
    }

    override fun awaitTermination(timeout: Long, unit: TimeUnit?): Boolean {
        return countDownLatch.await(timeout, unit)
    }

    private fun <T> delayedExecute(task: ScheduledFutureTask<T>): ScheduledFutureTask<T> {
        if (isShutdown) {
            throw RejectedExecutionException("Already shutdown")
        }
        queue.add(task)
        require(!(task.getDelay(TimeUnit.HOURS) > 24 && !task.isPeriodic)) {
            // guard against inadvertent queue overflow
            "Unsupported crazy delay " + task.getDelay(TimeUnit.MINUTES) + " minutes"
        }
        return task
    }

    override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
        val t = ScheduledFutureTask(
            command,
            Unit,
            triggerTime(queue, delay, unit)
        )
        return delayedExecute(t)
    }

    override fun <V : Any?> schedule(
        callable: Callable<V>,
        delay: Long,
        unit: TimeUnit
    ): ScheduledFuture<V> {
        val t = ScheduledFutureTask(
            callable,
            triggerTime(queue, delay, unit)
        )
        return delayedExecute(t)
    }

    override fun scheduleAtFixedRate(
        command: Runnable?,
        initialDelay: Long,
        period: Long,
        unit: TimeUnit?
    ): ScheduledFuture<*> {
        throw UnsupportedOperationException()
    }

    override fun scheduleWithFixedDelay(
        command: Runnable,
        initialDelay: Long,
        delay: Long,
        unit: TimeUnit
    ): ScheduledFuture<*> {
        require(delay > 0) { "delay must be positive but got: $delay" }
        val sft = ScheduledFutureTask(
            command,
            Unit,
            triggerTime(queue, initialDelay, unit),
            unit.toNanos(-delay)
        )
        return delayedExecute(sft)
    }
}