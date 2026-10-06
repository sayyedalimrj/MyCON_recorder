package ir.mycon.capture

import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.Camera
import com.google.ar.core.Pose
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class ArModelRenderer {
    private var program = 0
    private var positionLoc = 0
    private var mvpLoc = 0
    private var colorLoc = 0

    private val vertexBuffer: FloatBuffer =
        ByteBuffer
            .allocateDirect(VERTICES.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(VERTICES)
                position(0)
            }

    private val faceBuffer =
        ByteBuffer
            .allocateDirect(FACE_INDICES.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                put(FACE_INDICES)
                position(0)
            }

    private val edgeBuffer =
        ByteBuffer
            .allocateDirect(EDGE_INDICES.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                put(EDGE_INDICES)
                position(0)
            }

    fun create() {
        program =
            linkProgram(
                VERTEX_SHADER,
                FRAGMENT_SHADER
            )
        positionLoc =
            GLES20.glGetAttribLocation(
                program,
                "a_Position"
            )
        mvpLoc =
            GLES20.glGetUniformLocation(
                program,
                "u_Mvp"
            )
        colorLoc =
            GLES20.glGetUniformLocation(
                program,
                "u_Color"
            )
    }

    fun draw(
        camera: Camera,
        pose: Pose,
        sideLengthArUnits: Float
    ) {
        if (
            program == 0 ||
            sideLengthArUnits <= 0f ||
            camera.trackingState.name != "TRACKING"
        ) {
            return
        }

        val projection = FloatArray(16)
        val view = FloatArray(16)
        val model = FloatArray(16)
        val scaled = FloatArray(16)
        val mv = FloatArray(16)
        val mvp = FloatArray(16)

        camera.getProjectionMatrix(
            projection,
            0,
            0.05f,
            100f
        )
        camera.getViewMatrix(
            view,
            0
        )
        pose.toMatrix(
            model,
            0
        )

        val scale = FloatArray(16)
        Matrix.setIdentityM(scale, 0)
        Matrix.scaleM(
            scale,
            0,
            sideLengthArUnits,
            sideLengthArUnits,
            sideLengthArUnits
        )
        Matrix.multiplyMM(
            scaled,
            0,
            model,
            0,
            scale,
            0
        )
        Matrix.multiplyMM(
            mv,
            0,
            view,
            0,
            scaled,
            0
        )
        Matrix.multiplyMM(
            mvp,
            0,
            projection,
            0,
            mv,
            0
        )

        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(positionLoc)
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(
            positionLoc,
            3,
            GLES20.GL_FLOAT,
            false,
            0,
            vertexBuffer
        )
        GLES20.glUniformMatrix4fv(
            mvpLoc,
            1,
            false,
            mvp,
            0
        )

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(
            GLES20.GL_SRC_ALPHA,
            GLES20.GL_ONE_MINUS_SRC_ALPHA
        )

        faceBuffer.position(0)
        GLES20.glUniform4f(
            colorLoc,
            0.05f,
            0.72f,
            0.95f,
            0.16f
        )
        GLES20.glDrawElements(
            GLES20.GL_TRIANGLES,
            FACE_INDICES.size,
            GLES20.GL_UNSIGNED_BYTE,
            faceBuffer
        )

        edgeBuffer.position(0)
        GLES20.glLineWidth(5f)
        GLES20.glUniform4f(
            colorLoc,
            0.08f,
            0.88f,
            1.0f,
            0.95f
        )
        GLES20.glDrawElements(
            GLES20.GL_LINES,
            EDGE_INDICES.size,
            GLES20.GL_UNSIGNED_BYTE,
            edgeBuffer
        )

        // Back-face diagonal cross helps visually confirm orientation and scale.
        val crossBuffer =
            ByteBuffer
                .allocateDirect(CROSS_INDICES.size)
                .order(ByteOrder.nativeOrder())
                .apply {
                    put(CROSS_INDICES)
                    position(0)
                }
        GLES20.glUniform4f(
            colorLoc,
            1.0f,
            0.25f,
            0.28f,
            0.9f
        )
        GLES20.glDrawElements(
            GLES20.GL_LINES,
            CROSS_INDICES.size,
            GLES20.GL_UNSIGNED_BYTE,
            crossBuffer
        )

        GLES20.glDisableVertexAttribArray(positionLoc)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun compileShader(
        type: Int,
        source: String
    ): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(
            shader,
            GLES20.GL_COMPILE_STATUS,
            status,
            0
        )
        if (status[0] == 0) {
            val log =
                GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException(
                "AR model shader compile failed: $log"
            )
        }
        return shader
    }

    private fun linkProgram(
        vertex: String,
        fragment: String
    ): Int {
        val vertexShader =
            compileShader(
                GLES20.GL_VERTEX_SHADER,
                vertex
            )
        val fragmentShader =
            compileShader(
                GLES20.GL_FRAGMENT_SHADER,
                fragment
            )

        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vertexShader)
        GLES20.glAttachShader(p, fragmentShader)
        GLES20.glLinkProgram(p)

        val status = IntArray(1)
        GLES20.glGetProgramiv(
            p,
            GLES20.GL_LINK_STATUS,
            status,
            0
        )
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        if (status[0] == 0) {
            val log =
                GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw IllegalStateException(
                "AR model program link failed: $log"
            )
        }

        return p
    }

    companion object {
        // Unit cube. QR plane is z=0. The cube extends outward toward the
        // viewer along +Z, so it is visibly attached to the marker plane.
        private val VERTICES =
            floatArrayOf(
                -0.5f, -0.5f, 0f,
                0.5f, -0.5f, 0f,
                0.5f, 0.5f, 0f,
                -0.5f, 0.5f, 0f,
                -0.5f, -0.5f, 1f,
                0.5f, -0.5f, 1f,
                0.5f, 0.5f, 1f,
                -0.5f, 0.5f, 1f
            )

        private val FACE_INDICES =
            byteArrayOf(
                0, 1, 2, 0, 2, 3,
                4, 6, 5, 4, 7, 6,
                0, 4, 5, 0, 5, 1,
                1, 5, 6, 1, 6, 2,
                2, 6, 7, 2, 7, 3,
                3, 7, 4, 3, 4, 0
            )

        private val EDGE_INDICES =
            byteArrayOf(
                0, 1, 1, 2, 2, 3, 3, 0,
                4, 5, 5, 6, 6, 7, 7, 4,
                0, 4, 1, 5, 2, 6, 3, 7
            )

        private val CROSS_INDICES =
            byteArrayOf(
                4, 6,
                5, 7
            )

        private const val VERTEX_SHADER =
            "uniform mat4 u_Mvp;" +
                "attribute vec4 a_Position;" +
                "void main(){" +
                "gl_Position=u_Mvp*a_Position;" +
                "}"

        private const val FRAGMENT_SHADER =
            "precision mediump float;" +
                "uniform vec4 u_Color;" +
                "void main(){" +
                "gl_FragColor=u_Color;" +
                "}"
    }
}
