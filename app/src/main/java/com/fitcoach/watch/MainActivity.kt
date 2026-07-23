package com.fitcoach.watch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import kotlinx.coroutines.delay

/* ============================ ПРОГРАММА (та же, что в приложении) ============================ */
data class Phase(val name: String, val sets: Int, val reps: String, val rest: Int, val plank: Int)
fun prog(w: Int): Phase = when {
    w <= 2  -> Phase("Привыкание", 2, "12–15", 60, 20)
    w <= 4  -> Phase("База", 3, "12", 75, 30)
    w <= 6  -> Phase("Прогресс", 3, "10–12", 90, 40)
    w <= 8  -> Phase("Сила", 3, "10", 90, 45)
    w <= 10 -> Phase("Усложнение", 4, "8–10", 100, 50)
    else    -> Phase("Закрепление", 4, "8", 120, 60)
}
const val TOTAL_SESSIONS = 36
fun weekOf(done: Int) = minOf(12, done / 3 + 1)
fun whichDay(done: Int) = if (done % 2 == 0) "A" else "B"

data class ExDef(val name: String, val time: Boolean = false, val gym: Boolean = false)
val A_BASE = listOf(
    ExDef("Приседания"), ExDef("Отжимания"), ExDef("Тяга верхнего блока", gym = true),
    ExDef("Ягодичный мостик"), ExDef("Планка", time = true)
)
val A_UNLOCK = listOf(ExDef("Махи гантелями", gym = true) to 7)
val B_BASE = listOf(
    ExDef("Жим ногами", gym = true), ExDef("Жим гантелей сидя", gym = true),
    ExDef("Супермен"), ExDef("Скручивания"), ExDef("Боковая планка", time = true)
)
val B_UNLOCK = listOf(ExDef("Обратные выпады") to 5, ExDef("Становая тяга", gym = true) to 9)
val WARM = listOf("Прыжки «Джек»" to 40, "Бег с колен" to 30, "Захлёст голени" to 30, "Приседания" to 30, "Скалолаз" to 30)

data class SessionEx(val name: String, val sets: Int, val rest: Int, val target: String, val time: Boolean)
fun buildExercises(which: String, week: Int): List<SessionEx> {
    val p = prog(week)
    val base = if (which == "A") A_BASE else B_BASE
    val unlocks = (if (which == "A") A_UNLOCK else B_UNLOCK).filter { week >= it.second }.map { it.first }
    return (base + unlocks).map { ex ->
        val target = if (ex.time) "${p.plank} сек" else "${p.reps} повт."
        SessionEx(ex.name, p.sets, p.rest, target, ex.time)
    }
}

/* ============================ хранилище + вибрация ============================ */
object Prefs {
    private const val F = "fitwatch"; private const val K = "sessionsDone"
    fun getDone(c: Context) = c.getSharedPreferences(F, Context.MODE_PRIVATE).getInt(K, 0)
    fun setDone(c: Context, v: Int) = c.getSharedPreferences(F, Context.MODE_PRIVATE).edit().putInt(K, v).apply()
}
fun buzz(ctx: Context, ms: Long) {
    val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
    }
    if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    else @Suppress("DEPRECATION") v.vibrate(ms)
}

/* ============================ Activity ============================ */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colors = Colors(primary = Color(0xFFFF6B35), onPrimary = Color(0xFF10151B))) {
                App(this)
            }
        }
    }
}

private val FLAME = Color(0xFFFF6B35)
private val GOOD = Color(0xFF2DD4A7)
private val MUTED = Color(0xFF9AA1AC)

@Composable
fun colCenter(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

@Composable
fun App(ctx: Context) {
    var screen by remember { mutableStateOf("home") }
    var done by remember { mutableStateOf(Prefs.getDone(ctx)) }
    var warmIdx by remember { mutableStateOf(0) }
    var exIdx by remember { mutableStateOf(0) }

    // --- пульс (SensorManager, без доп. библиотек) ---
    var hr by remember { mutableStateOf(0) }
    var hasHrPerm by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED)
    }
    val hrLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> hasHrPerm = granted }
    LaunchedEffect(Unit) { if (!hasHrPerm) hrLauncher.launch(Manifest.permission.BODY_SENSORS) }
    val measuring = screen == "warm" || screen == "work"
    DisposableEffect(measuring, hasHrPerm) {
        var sm: SensorManager? = null
        var listener: SensorEventListener? = null
        if (measuring && hasHrPerm) {
            sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val sensor = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            if (sensor != null) {
                listener = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        if (e.values.isNotEmpty()) { val v = e.values[0].toInt(); if (v > 0) hr = v }
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
        onDispose { val s = sm; val l = listener; if (s != null && l != null) s.unregisterListener(l) }
    }
    val hrText = if (hasHrPerm) "❤️ " + (if (hr > 0) "$hr" else "—") else ""

    val week = weekOf(done)
    val w = whichDay(done)
    val phase = prog(week)
    val exercises = remember(done) { buildExercises(w, week) }

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
    ) {
        when (screen) {
            /* ---------- HOME ---------- */
            "home" -> colCenter {
                Text("🏋️ Личный тренер", fontSize = 13.sp, color = MUTED)
                Spacer(Modifier.height(4.dp))
                if (done >= TOTAL_SESSIONS)
                    Text("Программа пройдена 🎉", fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                else
                    Text("Неделя $week / 12", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = FLAME)
                Text(phase.name, fontSize = 13.sp, color = MUTED)
                Spacer(Modifier.height(6.dp))
                Text("День $w", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Разминка + ${exercises.size} упр.", fontSize = 12.sp, color = MUTED, textAlign = TextAlign.Center)
                Text("${phase.sets} подхода · ${phase.reps} повт.", fontSize = 12.sp, color = MUTED, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Chip(
                    onClick = { warmIdx = 0; exIdx = 0; screen = "warm" },
                    colors = ChipDefaults.primaryChipColors(),
                    label = { Text("▶ Начать") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text("Пройдено ${minOf(done, TOTAL_SESSIONS)}/$TOTAL_SESSIONS", fontSize = 11.sp, color = MUTED)
            }

            /* ---------- WARM-UP ---------- */
            "warm" -> {
                val wm = WARM[warmIdx]
                var left by remember(warmIdx) { mutableStateOf(wm.second) }
                LaunchedEffect(warmIdx) {
                    while (left > 0) { delay(1000); left-- }
                    buzz(ctx, 250)
                    if (warmIdx < WARM.size - 1) warmIdx++ else { screen = "work"; warmIdx = 0 }
                }
                colCenter {
                    Text("🔥 Разминка ${warmIdx + 1}/${WARM.size}", fontSize = 12.sp, color = MUTED)
                    if (hrText.isNotEmpty()) Text(hrText, fontSize = 12.sp, color = FLAME)
                    Spacer(Modifier.height(4.dp))
                    Text(wm.first, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text("$left", fontSize = 46.sp, fontWeight = FontWeight.Bold, color = FLAME)
                    Spacer(Modifier.height(10.dp))
                    Chip(onClick = { if (warmIdx < WARM.size - 1) warmIdx++ else { screen = "work"; warmIdx = 0 } },
                        colors = ChipDefaults.primaryChipColors(), label = { Text("Дальше ⏭") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Chip(onClick = { screen = "work"; warmIdx = 0 },
                        colors = ChipDefaults.secondaryChipColors(), label = { Text("Пропустить") }, modifier = Modifier.fillMaxWidth())
                }
            }

            /* ---------- WORKOUT ---------- */
            "work" -> {
                val ex = exercises[exIdx]
                var setDone by remember(exIdx) { mutableStateOf(0) }
                var resting by remember(exIdx) { mutableStateOf(false) }
                var restLeft by remember { mutableStateOf(0) }
                LaunchedEffect(resting) {
                    if (resting) {
                        while (restLeft > 0 && resting) { delay(1000); restLeft-- }
                        if (resting) { buzz(ctx, 450); resting = false }
                    }
                }
                val goNext: () -> Unit = {
                    if (exIdx < exercises.size - 1) exIdx++
                    else { done += 1; Prefs.setDone(ctx, done); buzz(ctx, 600); screen = "done"; exIdx = 0 }
                }
                colCenter {
                    Text("Тр. $w · нед $week" + if (hrText.isNotEmpty()) "   $hrText" else "", fontSize = 11.sp, color = MUTED)
                    Text("${exIdx + 1}/${exercises.size}: ${ex.name}", fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("$setDone/${ex.sets} подх · ${ex.target}", fontSize = 12.sp, color = MUTED, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    if (resting) {
                        Text("Отдых", fontSize = 12.sp, color = MUTED)
                        Text("$restLeft", fontSize = 46.sp, fontWeight = FontWeight.Bold, color = FLAME)
                        Spacer(Modifier.height(8.dp))
                        Chip(onClick = { restLeft += 15 }, colors = ChipDefaults.secondaryChipColors(),
                            label = { Text("+15 сек") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Chip(onClick = { resting = false }, colors = ChipDefaults.primaryChipColors(),
                            label = { Text("Пропустить отдых") }, modifier = Modifier.fillMaxWidth())
                    } else {
                        Chip(
                            onClick = {
                                setDone++; buzz(ctx, 60)
                                if (setDone < ex.sets) { restLeft = ex.rest; resting = true } else goNext()
                            },
                            colors = ChipDefaults.primaryChipColors(),
                            label = { Text(if (ex.time) "Готово ⏱ ✓" else "Подход готов ✓") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Chip(onClick = goNext, colors = ChipDefaults.secondaryChipColors(),
                            label = { Text("Следующее ⏭") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Chip(onClick = { done += 1; Prefs.setDone(ctx, done); buzz(ctx, 600); screen = "done"; exIdx = 0 },
                            colors = ChipDefaults.secondaryChipColors(),
                            label = { Text("Финиш") }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            /* ---------- DONE ---------- */
            else -> colCenter {
                Text("💪", fontSize = 34.sp)
                Text("Тренировка готова!", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = GOOD, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(if (done >= TOTAL_SESSIONS) "Вся программа пройдена 🎉" else "Пройдено $done/$TOTAL_SESSIONS",
                    fontSize = 12.sp, color = MUTED, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Chip(onClick = { screen = "home" }, colors = ChipDefaults.primaryChipColors(),
                    label = { Text("На главную") }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
