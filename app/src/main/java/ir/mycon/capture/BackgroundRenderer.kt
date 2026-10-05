package ir.mycon.capture

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class BackgroundRenderer {
    var textureId: Int = -1
        private set

    private var program = 0
    private var posLoc = 0
    private var uvLoc = 0
    private var samplerLoc = 0

    private val quad = floatBuffer(
        floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
    )

    private val transformedUv = floatBuffer(FloatArray(8))
    private val ndc = floatArrayOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f
    )

    fun create() {
        val texture = IntArray(1)
        GLES20.glGenTextures(1, texture, 0)
        textureId = texture[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )

        program = link(VERTEX_SHADER, FRAGMENT_SHADER)
        posLoc = GLES20.glGetAttribLocation(program, "a_Position")
        uvLoc = GLES20.glGetAttribLocation(program, "a_TexCoord")
        samplerLoc = GLES20.glGetUniformLocation(program, "sTexture")
    }

    fun draw(frame: Frame) {
        val uv = FloatArray(8)
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            ndc,
            Coordinates2d.TEXTURE_NORMALIZED,
            uv
        )

        transformedUv.position(0)
        transformedUv.put(uv)
        transformedUv.position(0)
        quad.position(0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(samplerLoc, 0)

        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(
            posLoc,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            quad
        )

        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(
            uvLoc,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            transformedUv
        )

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
        GLES20.glDepthMask(true)
    }

    private fun compileShader(type: Int, source: String): Int =
        GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source)
            GLES20.glCompileShader(it)
        }

    private fun link(vertex: String, fragment: String): Int =
        GLES20.glCreateProgram().also {
            GLES20.glAttachShader(
                it,
                compileShader(GLES20.GL_VERTEX_SHADER, vertex)
            )
            GLES20.glAttachShader(
                it,
                compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
            )
            GLES20.glLinkProgram(it)
        }

    companion object {
        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }

        private const val VERTEX_SHADER =
            "attribute vec4 a_Position;" +
                "attribute vec2 a_TexCoord;" +
                "varying vec2 v_TexCoord;" +
                "void main(){" +
                "gl_Position=a_Position;" +
                "v_TexCoord=a_TexCoord;" +
                "}"

        private const val FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;" +
                "varying vec2 v_TexCoord;" +
                "uniform samplerExternalOES sTexture;" +
                "void main(){" +
                "gl_FragColor=texture2D(sTexture,v_TexCoord);" +
                "}"
    }
}
