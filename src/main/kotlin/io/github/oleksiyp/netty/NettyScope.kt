package io.github.oleksiyp.netty

import io.netty.channel.Channel
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.LinkedListChannel
import java.nio.channels.ClosedChannelException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

open class NettyScope<I>(internal val internal: Internal<I>) {
    val isActive
        get() = internal.isActive()

    suspend fun receive(): I = internal.receive()
    suspend fun skipAllReceived() = internal.skipAllReceived()

    suspend fun receive(block: suspend (I) -> Unit) {
        val msg = receive()
        try {
            block.invoke(msg)
        } finally {
            ReferenceCountUtil.release(msg)
        }
    }

    suspend fun send(obj: Any) {
        internal.send(obj)
    }

    class Internal<I>(val channel: Channel,
                      private val dispatcher: CoroutineDispatcher,
                      private val writeability: Boolean = true) {

        lateinit var job: Job
        fun isActive() = job.isActive

        fun readabilityChanged(newValue: Boolean) {
            val chCfg = channel.config()
            if (chCfg.isAutoRead != newValue) {
                chCfg.isAutoRead = newValue
            }
        }

        fun write(msg: Any) = channel.writeAndFlush(msg)

        fun isWritable() = if (writeability) channel.isWritable else true

        val onCloseHandlers = mutableListOf<suspend () -> Unit>()

        private val readabilityBarrier = ReadabilityBarrier(10)
        private val writeContinuation = AtomicReference<CancellableContinuation<Unit>>()
        private val receiveChannel = LinkedListChannel<I>()

        fun dataReceived(msg: I) {
            readabilityBarrier.changeReadability(receiveChannel.isEmpty)
            receiveChannel.offer(msg)
        }

        suspend fun receive(): I {
            val data = receiveChannel.poll()
            if (data == null) {
                readabilityBarrier.changeReadability(true)
                return receiveChannel.receive()
            }
            readabilityBarrier.changeReadability(receiveChannel.isEmpty)
            return data
        }

        suspend fun skipAllReceived() {
            receiveChannel.close()
        }

        inner class ReadabilityBarrier(val threshold: Int) {
            private val nNonReadable = AtomicInteger()
            fun changeReadability(readability: Boolean) {
                if (readability) {
                    readabilityChanged(true)
                    nNonReadable.set(0)
                } else {
                    if (nNonReadable.incrementAndGet() > threshold) {
                        readabilityChanged(false)
                    }
                }
            }
        }

        suspend fun send(msg: Any) {
            if (!isWritable()) {
                suspendCancellableCoroutine<Unit> { cont ->
                    writeContinuation.getAndSet(cont)?.resume(Unit)
                }
            }

            suspendCancellableCoroutine<Unit> { cont ->
                val future = write(msg)
                cont.cancelFutureOnCompletion(future)
                future.addListener {
                    if (future.isSuccess) {
                        cont.resume(Unit)
                    } else if (future.cause() is ClosedChannelException) {
                        job.cancel()
                        cont.resume(Unit)
                    } else {
                        cont.resumeWithException(future.cause())
                    }
                }
            }
        }


        fun resumeWrite() {
            val cont = writeContinuation.getAndSet(null)
            if (cont != null) {
                cont.resume(Unit)
            }
        }

        suspend fun notifyCloseHandlers() {
            for (handler in onCloseHandlers) {
                handler()
            }
        }

        suspend fun cancelJob() {
            job.cancel()
            job.join()
        }

        fun go(block: suspend () -> Unit) {
            val q = ArrayBlockingQueue<CancellableContinuation<Unit>>(1)
            job = launch(dispatcher) {
                suspendCancellableCoroutine<Unit> { cont -> q.put(cont) }
                // after this line job is assigned
                block()
            }
            val cont = q.take()
            cont.resume(Unit)
        }


    }



    suspend fun List<Job>.mutualClose() {
        forEach {
            it.invokeOnCompletion {
                forEach { c -> if (c != it) c.cancel() }
            }
        }
    }

    suspend fun List<NettyScope<*>>.scopesMutualClose() {
        map { it.internal.job }.mutualClose()
    }
}
