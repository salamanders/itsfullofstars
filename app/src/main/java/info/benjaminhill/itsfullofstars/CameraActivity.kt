package info.benjaminhill.itsfullofstars

import android.Manifest
import android.view.Menu
import android.view.MenuItem
import java.util.logging.Logger


class CameraActivity : EZPermissionActivity() {
    init {
        requiredPermissions.addAll(setOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }

    override fun run() {
        System.setProperty("kotlinx.coroutines.debug", "")

        setContentView(R.layout.activity_camera)

        /*
        setSupportActionBar(toolbar)

        fab.setOnClickListener { view ->
            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show()
        }
        */
        SimpleCamera(this).use { c2s ->
            Log.info("Ready to take a shot in camera Mode: ${c2s.mode}")
            try {
                c2s.click()
            } catch (e: Throwable) {
                Log.severe("Died during click: $e")
                e.printStackTrace()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_camera, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private val Log = Logger.getLogger(SimpleCamera::class.java.simpleName)
    }


}
