package info.benjaminhill.itsfullofstars


import android.view.Menu
import android.view.MenuItem
import kotlinx.android.synthetic.main.activity_camera.*
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.runBlocking
import java.util.logging.Logger


class CameraActivity : EZPermissionActivity() {


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
        SimpleCamera(this, cameraSurfaceView).use { c2s ->
            Log.info("Ready to take a shot in camera Mode: ${c2s.mode}")
            try {
                val clicks = mutableSetOf<Deferred<String>>()
                for (i in 0..3) {
                    clicks.add(c2s.click())
                }
                Log.severe("CLICKED A BUNCH OF TIMES")

                Log.severe("WAITING FOR FIRST 2")
                runBlocking {
                    clicks.take(2).forEach {
                        Log.severe(it.await())
                    }
                }
                Log.severe("SAVED FIRST 2")
                clicks.add(c2s.click())

                runBlocking {
                    Log.severe("SAVING THE REST")
                    clicks.forEach {
                        it.await()
                    }
                }

                Log.severe("THROWAWAY CLICK")
                clicks.add(c2s.click())
            } catch (e: Throwable) {
                Log.severe("Died during click: $e")
                Log.severe(e.stackTrace.joinToString("\n"))
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
