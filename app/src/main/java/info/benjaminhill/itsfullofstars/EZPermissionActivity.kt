package info.benjaminhill.itsfullofstars

import android.content.pm.PackageManager
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import java.util.logging.Logger

/**
 * Work out the permissions before diving into the app
 */
abstract class EZPermissionActivity : AppCompatActivity() {
    protected val requiredPermissions: MutableSet<String> = mutableSetOf()

    abstract fun run()

    final override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Don't ignore exceptions in coroutines https://github.com/Kotlin/kotlinx.coroutines/issues/148#issuecomment-338101986
        val baseUEH = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // this may double log the error on older versions of android
            Log.severe("FATAL EXCEPTION: ${thread.name} $error")
            Log.severe(error.stackTrace.joinToString("\n"))
            baseUEH.uncaughtException(thread, error)
            throw error
        }

        if (missingPermissions.isEmpty()) {
            Log.info("Had all permissions the first time.")
            run()
        } else {
            Log.warning("Missing permissions: ${missingPermissions.joinToString()}")
            // TODO: The "right" way with requesting permissions giving reasons
            ActivityCompat.requestPermissions(this, missingPermissions, SIMPLE_PERMISSION_ID)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        Log.info("Permission results: $requestCode, $permissions, $grantResults")
        if (requestCode == SIMPLE_PERMISSION_ID) {
            if (missingPermissions.isEmpty()) {
                Log.info("User granted all permissions!")
                run()
            } else {
                TODO("Still missing permissions.  :(")
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private val missingPermissions: Array<String>
        get() = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()


    companion object {
        const val SIMPLE_PERMISSION_ID = 42
        private val Log = Logger.getLogger(SimpleCamera::class.java.simpleName)
    }
}