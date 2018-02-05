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