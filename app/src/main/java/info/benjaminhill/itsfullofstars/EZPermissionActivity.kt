package info.benjaminhill.itsfullofstars

import android.content.pm.PackageManager
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.widget.Toast

/**
 * Work out the dangerous permissions listed in the AndroidManifest.xml (dynamically) before diving into the app `runWithPermissions { yourCode }`
 * TODO: Should this be a headless fragment?
 */
abstract class EZPermissionActivity : AppCompatActivity() {

    private lateinit var permissionCallback: () -> Unit

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Don't ignore exceptions in coroutines https://github.com/Kotlin/kotlinx.coroutines/issues/148#issuecomment-338101986
        val baseUEH = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // this may double log the error on older versions of android
            Log.w(TAG, "FATAL EXCEPTION: ${thread.name} $error")
            Log.w(TAG, error)
            baseUEH.uncaughtException(thread, error)
            throw error
        }
    }

    protected fun runWithPermissions(f: () -> Unit) = if (missingPermissions.isEmpty()) {
        Log.i(TAG, "We already have all the permissions (${requiredPermissions.joinToString()}) we needed, running directly")
        f()
    } else {
        // TODO: The "right" way with requesting permissions giving reasons
        Log.i(TAG, "Requesting permissions, be back soon.")
        logPermissions()
        permissionCallback = f
        ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), SIMPLE_PERMISSION_ID)
    }

    override fun onRequestPermissionsResult(requestCode: Int, grantPermissions: Array<out String>, grantResults: IntArray) =
            if (requestCode == SIMPLE_PERMISSION_ID) {
                Log.i(TAG, "Permission grant result: ${grantPermissions.joinToString()}=${grantResults.joinToString()}")
                logPermissions()
                if (missingPermissions.isEmpty()) {
                    Log.i(TAG, "User granted all permissions that we requested.")
                    permissionCallback()
                } else {
                    Log.w(TAG, "User declined required permissions: ${missingPermissions.joinToString()}")
                    Toast.makeText(this, "Please restart the app after allowing access to: ${missingPermissions.joinToString()}", Toast.LENGTH_LONG).show()
                }
            } else {
                super.onRequestPermissionsResult(requestCode, grantPermissions, grantResults)
            }

    private fun logPermissions() {
        requiredPermissions.forEach {
            Log.i(TAG, "Permission: $it; missing: ${it in missingPermissions}")
        }
    }

    private val requiredPermissions
        get() = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
                .toSet()

    private val missingPermissions
        get() = requiredPermissions
                .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
                .toSet()

    companion object {
        private const val SIMPLE_PERMISSION_ID = 42
        private const val TAG = "ifos"
    }
}