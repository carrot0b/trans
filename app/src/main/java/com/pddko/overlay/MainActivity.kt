package com.pddko.overlay

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : Activity() {

    private val reqCapture = 7
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private var modelReady = false
    private var downloading = false

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(36f), dp(20f), dp(24f))
        }
        col.addView(TextView(this).apply {
            text = "핀둬둬 한글화"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(224, 46, 36))
        })
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(12f), 0, dp(16f))
            setLineSpacing(0f, 1.35f)
        }
        col.addView(status)

        fun button(label: String, onClick: () -> Unit) {
            col.addView(Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 16f
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
        }

        button("① 접근성 서비스 켜기") { openAccessibility() }
        button("② 번역 모델 받기 (최초 1회, 약 30MB)") { downloadModel() }
        button("③ 번역 시작 → 핀둬둬 열기") { startCapture() }
        button("번역 완전히 끄기") {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            main.postDelayed({ refresh() }, 300)
        }

        col.addView(TextView(this).apply {
            textSize = 13.5f
            setTextColor(Color.DKGRAY)
            setLineSpacing(0f, 1.4f)
            setPadding(0, dp(20f), 0, 0)
            text = """
                사용법
                • ③을 누르고 '전체 화면' 녹화를 허용하면 핀둬둬가 열립니다. 이후로는 핀둬둬를 열 때마다 자동으로 한글이 덮어집니다.
                • 스크롤하면 번역도 같이 움직이고, 손을 멈추면 0.3초쯤 뒤 새로 보이는 부분이 번역됩니다.
                • 오른쪽의 빨간 '译→한' 버튼
                   - 탭: 복사 모드 (번역을 길게 누르거나 탭 → '번역 복사/원문 복사')
                   - 길게 누르기: 번역 일시정지/재개
                   - 끌어서 위치 이동
                • 휴대폰을 재부팅하거나 알림에서 '중지'를 누르면 ③을 다시 눌러야 합니다.

                접근성 메뉴에 이 앱이 회색으로 막혀 있으면
                설정 → 애플리케이션 → 핀둬둬 한글화 → 오른쪽 위 ⋮ → '제한된 설정 허용' 후 다시 켜세요.

                화면 내용은 기기 안에서만 인식·번역되며 외부로 보내지 않습니다.
            """.trimIndent()
        })

        setContentView(ScrollView(this).apply { addView(col) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        checkModel()
        refresh()
    }

    private fun a11yEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(this, TranslatorService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun refresh() {
        fun mark(ok: Boolean) = if (ok) "✅" else "⬜"
        val pddInstalled = packageManager.getLaunchIntentForPackage(TranslatorService.PDD) != null
        status.text = buildString {
            append("${mark(a11yEnabled())} 접근성 서비스\n")
            append("${mark(modelReady)} 중국어→한국어 번역 모델")
            if (downloading) append(" (받는 중…)")
            append("\n${mark(FrameHub.running)} 화면 번역 실행 중\n")
            if (!pddInstalled) append("\n⚠️ 핀둬둬 앱이 설치되어 있지 않습니다.")
        }
    }

    private fun openAccessibility() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "'설치된 앱' → '핀둬둬 한글화'를 켜 주세요", Toast.LENGTH_LONG).show()
    }

    private fun options() = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.CHINESE)
        .setTargetLanguage(TranslateLanguage.KOREAN)
        .build()

    private fun checkModel() {
        val mgr = RemoteModelManager.getInstance()
        val ko = mgr.isModelDownloaded(TranslateRemoteModel.Builder(TranslateLanguage.KOREAN).build())
        val zh = mgr.isModelDownloaded(TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build())
        ko.addOnSuccessListener { k ->
            zh.addOnSuccessListener { z ->
                modelReady = k && z
                refresh()
            }
        }
    }

    private fun downloadModel() {
        if (modelReady || downloading) return
        downloading = true
        refresh()
        val t = Translation.getClient(options())
        t.downloadModelIfNeeded()
            .addOnSuccessListener {
                downloading = false; modelReady = true; refresh()
                Toast.makeText(this, "번역 모델 준비 완료", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                downloading = false; refresh()
                Toast.makeText(this, "다운로드 실패: 인터넷 연결을 확인하세요", Toast.LENGTH_LONG).show()
            }
            .addOnCompleteListener { t.close() }
    }

    private fun startCapture() {
        if (!a11yEnabled()) {
            Toast.makeText(this, "먼저 ① 접근성 서비스를 켜 주세요", Toast.LENGTH_LONG).show()
            return
        }
        if (!modelReady) downloadModel()
        if (FrameHub.running) {
            launchPdd(); return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqCapture) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "화면 녹화를 허용해야 번역할 수 있어요", Toast.LENGTH_LONG).show()
            return
        }
        startForegroundService(
            Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data)
        )
        main.postDelayed({ refresh(); launchPdd() }, 500)
    }

    private fun launchPdd() {
        val i = packageManager.getLaunchIntentForPackage(TranslatorService.PDD)
        if (i != null) {
            startActivity(i)
        } else {
            Toast.makeText(this, "핀둬둬가 설치되어 있지 않습니다", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${TranslatorService.PDD}")))
            } catch (e: Exception) {
            }
        }
    }
}
