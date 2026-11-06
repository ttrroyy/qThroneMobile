package app.qthrone.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.*
import app.qthrone.mobile.R
import app.qthrone.mobile.deploy.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class DeployActivity : ServerScreenActivity() {
    lateinit var toolbar: Toolbar
    class EditorState : androidx.lifecycle.ViewModel() { var server: DeployServer? = null;var baseline: String? = null }
    private val editorState by lazy { androidx.lifecycle.ViewModelProvider(this)[EditorState::class.java] }
    var server: DeployServer?
        get() = editorState.server
        set(value) { editorState.server=value;editorState.baseline=value?.editSignature() }
    fun hasChanges() = server?.editSignature()!=editorState.baseline
    private var shownTrust = ""
    private var shownQuestion: DeployQuestion? = null
    private var shownConfig = ""
    private var wasRunning = false
    val kind get() = intent.getStringExtra("kind").orEmpty().takeIf { it in listOf("qwdtt","csqtt","wdttplus") } ?: "qwdtt"
    private val keyPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if(uri != null) lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { runCatching {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                    while(true) { val count=stream.read(buffer);if(count<0) break;require(output.size()+count<=65536);output.write(buffer,0,count) };String(output.toByteArray(),Charsets.UTF_8)
                } ?: error("Нет доступа к файлу")
            } }
            text.onSuccess { key -> server?.let { it["key"]=key;it["keyName"]=uri.lastPathSegment.orEmpty();screen()?.build() } }
                .onFailure { Toast.makeText(this@DeployActivity,"Не удалось прочитать SSH-ключ",Toast.LENGTH_LONG).show() }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(MobilePalette.background))
        shownConfig=savedInstanceState?.getString("shownConfig").orEmpty()
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(MobilePalette.screen) }
        toolbar=Toolbar(this).apply { setTitleTextColor(MobilePalette.text);setNavigationIcon(R.drawable.baseline_arrow_back_24);setNavigationOnClickListener { back() } }
        val content=FrameLayout(this).apply { id=android.R.id.content+3 }
        val loading=ProgressBar(this)
        content.addView(loading,FrameLayout.LayoutParams(-2,-2,android.view.Gravity.CENTER))
        root.addView(toolbar);root.addView(content,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets -> val b=insets.getInsets(WindowInsetsCompat.Type.systemBars());view.setPadding(b.left,b.top,b.right,b.bottom);insets }
        val id=savedInstanceState?.getString("server") ?: intent.getStringExtra("editServer")
        onBackPressedDispatcher.addCallback(this,object : androidx.activity.OnBackPressedCallback(true) { override fun handleOnBackPressed() { back() } })
        lifecycleScope.launch {
            if (server == null && id != null) {
                val result = withContext(Dispatchers.IO) { runCatching { DeployStore.all().firstOrNull { it.id == id } } }
                server = result.getOrNull()
                if (server == null) {
                    Toast.makeText(this@DeployActivity,"Не удалось открыть VPS",Toast.LENGTH_LONG).show()
                    finish();return@launch
                }
            } else if (server == null && intent.getBooleanExtra("directEditor",false)) server = DeployServer.create(kind)
            content.removeView(loading)
            if(savedInstanceState==null) supportFragmentManager.beginTransaction().replace(content.id,Screen()).commitNow()
            else screen()?.build()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DeployEngine.state.collect { state ->
                    screen()?.render(state)
                    if(wasRunning && !state.running && state.percent==100) Toast.makeText(this@DeployActivity,state.step,Toast.LENGTH_LONG).show()
                    wasRunning=state.running
                    if(state.trust.isNotBlank() && state.trust!=shownTrust) {
                        shownTrust=state.trust
                        MobileAlertDialogBuilder(this@DeployActivity).setTitle("Ключ сервера SSH")
                            .setMessage("Сверьте отпечаток с ключом VPS:\n${state.trust}")
                            .setPositiveButton("Доверять") { _,_ -> DeployEngine.approveHost(true) }
                            .setNegativeButton(android.R.string.cancel) { _,_ -> DeployEngine.approveHost(false) }
                            .setOnCancelListener { DeployEngine.approveHost(false) }.show()
                    }
                    val question=state.question
                    if(question!=null && question!==shownQuestion) {
                        shownQuestion=question
                        MobileAlertDialogBuilder(this@DeployActivity).setTitle(question.title).setMessage(question.message).apply {
                            question.options.getOrNull(0)?.let { setPositiveButton(it) { _,_ -> DeployEngine.answer(0) } }
                            question.options.getOrNull(1)?.let { setNeutralButton(it) { _,_ -> DeployEngine.answer(1) } }
                        }.setNegativeButton(android.R.string.cancel) { _,_ -> DeployEngine.answer(-1) }
                            .setOnCancelListener { DeployEngine.answer(-1) }.show()
                    }
                }
            }
        }
    }
    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if(android.os.Build.VERSION.SDK_INT<34) overridePendingTransition(0,0)
    }
    override fun onSaveInstanceState(outState: Bundle) { server?.let { outState.putString("server",it.id) };outState.putString("shownConfig",shownConfig);super.onSaveInstanceState(outState) }
    private fun screen() = supportFragmentManager.fragments.filterIsInstance<Screen>().firstOrNull()
    private fun back() { if(intent.getBooleanExtra("directEditor",false)) finish() else if(server!=null) { server=null;screen()?.build() } else finish() }
    fun saved(signature: String) {
        editorState.baseline=signature
        screen()?.refreshSaveAction()
    }
    fun pickKey() { keyPicker.launch("*/*") }
    fun start(uninstall: Boolean) {
        val target=server ?: return
        if(DeployStore.all().none { it.id==target.id }) {
            MobileAlertDialogBuilder(this).setMessage("Сначала сохраните VPS кнопкой в верхнем меню.").setPositiveButton(android.R.string.ok,null).show()
            return
        }
        if(DeployEngine.state.value.running) { Toast.makeText(this,"Дождитесь завершения текущей операции",Toast.LENGTH_SHORT).show();return }
        val valid=runCatching { target.validate(!uninstall) }
        if(valid.isFailure) { MobileAlertDialogBuilder(this).setMessage(valid.exceptionOrNull()?.message).setPositiveButton(android.R.string.ok,null).show();return }
        fun run(removeData: Boolean = false) { DeployEngine.start(target,uninstall,removeData);shownConfig="";shownTrust="";showConsole() }
        // WDTT Plus removes the service either keeping /etc/wdtt (clients, keys) for a later install or with it.
        if(uninstall && kind=="wdttplus") MobileAlertDialogBuilder(this).setTitle("Удалить WDTT Plus с VPS?")
            .setMessage("Сервис будет удалён с ${target["host"]}. Можно сохранить данные (клиентов, ключи WireGuard) для повторной установки или удалить их вместе с сервисом.")
            .setPositiveButton("Сохранить данные") { _,_ -> run(false) }.setNeutralButton("Удалить всё") { _,_ -> run(true) }
            .setNegativeButton(android.R.string.cancel,null).show()
        else if(uninstall) MobileAlertDialogBuilder(this).setTitle("Удалить сервис с VPS?").setMessage("Будет запущено удаление ${title(kind)} на ${target["host"]}.")
            .setPositiveButton("Удалить") { _,_ -> run() }.setNegativeButton(android.R.string.cancel,null).show()
        else run()
    }
    private fun showConsole() { screen()?.revealConsole() }
    class Screen : DeployEditorFragment()
    companion object {
        fun title(kind: String) = when(kind) { "csqtt" -> "CSQTT"; "wdttplus" -> "WDTT Plus"; else -> "qWDTT" }
    }
}
