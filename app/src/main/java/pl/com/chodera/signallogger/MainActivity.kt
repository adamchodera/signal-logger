package pl.com.chodera.signallogger

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import pl.com.chodera.signallogger.ui.MainScreen
import pl.com.chodera.signallogger.ui.MainViewModel
import pl.com.chodera.signallogger.ui.UiEffect
import pl.com.chodera.signallogger.ui.theme.SignalLoggerTheme

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect { effect -> handle(effect) }
            }
        }

        setContent {
            SignalLoggerTheme {
                MainScreen(viewModel)
            }
        }
    }

    private fun handle(effect: UiEffect) {
        when (effect) {
            is UiEffect.LaunchIntent -> try {
                startActivity(effect.intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "Brak aplikacji, która obsłuży to działanie", Toast.LENGTH_LONG).show()
            }
        }
    }
}
