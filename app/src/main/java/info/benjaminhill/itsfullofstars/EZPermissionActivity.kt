package info.benjaminhill.itsfullofstars

import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.widget.Toast
import java.util.logging.Logger

/**
 * Work out the dangerous permissions (dynamically) before diving into the app
 * TODO: Should this be a headless fragment?
 */
abstract class EZPermissionActivity : AppCompatActivity() {
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
            Log.info("We already had all the dangerous permissions we needed (yay!)")
            run()
        }

        // TODO: The "right" way with requesting permissions giving reasons
        Log.info("Requesting dangerous permission to $missingPermissions.")
        ActivityCompat.requestPermissions(this, missingPermissions, SIMPLE_PERMISSION_ID)
    }

    override fun onRequestPermissionsResult(requestCode: Int, grantPermissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == SIMPLE_PERMISSION_ID) {
            Log.info("Permission grant result: ${grantPermissions.joinToString()}=${grantResults.joinToString()}")
            if (missingPermissions.isEmpty()) {
                Log.info("User granted all permissions!")
                run()
            } else {
                Toast.makeText(this, "You must allow access to the camera and to write to external storage.", Toast.LENGTH_LONG).show()
                TODO("Still missing permissions.  :(")
            }
        } else {
            super.onRequestPermissionsResult(requestCode, grantPermissions, grantResults)
        }
    }

    private val missingPermissions: Array<String>
        get() = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
                .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
                .filter { packageManager.getPermissionInfo(it, PackageManager.GET_META_DATA).protectionLevel == PermissionInfo.PROTECTION_DANGEROUS }
                .toTypedArray()

    companion object {
        const val SIMPLE_PERMISSION_ID = 42
        private val Log = Logger.getLogger(EZPermissionActivity::class.java.simpleName)
    }
}