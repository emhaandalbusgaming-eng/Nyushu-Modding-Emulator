package dev.nyushu.optimizer.ui

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

object Async {
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    fun <T> run(work: () -> T, done: (Result<T>) -> Unit) {
        pool.execute { val r = runCatching(work); main.post { done(r) } }
    }
}
