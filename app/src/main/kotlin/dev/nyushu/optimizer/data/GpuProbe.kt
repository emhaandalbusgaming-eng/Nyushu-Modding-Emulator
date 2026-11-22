package dev.nyushu.optimizer.data

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import dev.nyushu.optimizer.core.model.Reading

data class GlInfo(val renderer: Reading<String>, val vendor: Reading<String>, val version: Reading<String>)

/** Reads GL_RENDERER/VENDOR/VERSION from a throw-away 1x1 EGL pbuffer context. No root, no permissions. */
object GpuProbe {
    fun query(): GlInfo {
        val na = { why: String -> GlInfo(Reading.NotAvailable(why), Reading.NotAvailable(why), Reading.NotAvailable(why)) }
        var display = EGL14.EGL_NO_DISPLAY
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return na("EGL display unavailable")
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return na("eglInitialize failed")
            val attribs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) return na("no EGL config")
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return na("eglMakeCurrent failed")
            fun s(name: Int) = GLES20.glGetString(name)?.let { Reading.Value(it) } ?: Reading.NotAvailable("glGetString returned null")
            return GlInfo(s(GLES20.GL_RENDERER), s(GLES20.GL_VENDOR), s(GLES20.GL_VERSION))
        } catch (e: Throwable) {
            return na("GL probe failed: ${e.javaClass.simpleName}")
        } finally {
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            } catch (_: Throwable) {}
        }
    }
}
