package com.juhyun.oddcamera.gl

import android.opengl.GLES30

/** Compiles + links a program; throws IllegalStateException with the driver log on failure. */
class GlProgram(vertexSrc: String, fragmentSrc: String) {
    val id: Int

    init {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vs)
        GLES30.glAttachShader(id, fs)
        GLES30.glLinkProgram(id)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(id)
            GLES30.glDeleteProgram(id)
            throw IllegalStateException("program link failed: $log")
        }
    }

    fun use() = GLES30.glUseProgram(id)
    fun uniform(name: String): Int = GLES30.glGetUniformLocation(id, name)
    fun attrib(name: String): Int = GLES30.glGetAttribLocation(id, name)
    fun release() = GLES30.glDeleteProgram(id)

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            GLES30.glDeleteShader(s)
            throw IllegalStateException("shader compile failed (type 0x${Integer.toHexString(type)}): $log")
        }
        return s
    }
}
