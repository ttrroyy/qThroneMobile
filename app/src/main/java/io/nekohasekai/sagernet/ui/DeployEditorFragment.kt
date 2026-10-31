package app.qthrone.mobile.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.qthrone.mobile.R
import app.qthrone.mobile.deploy.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText

/** Compact author-style deployment form with an inline, collapsible VPS console. */
open class DeployEditorFragment : Fragment() {
    private val host get()=requireActivity() as DeployActivity
    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout
    private var consoleCard: View?=null
    private var details: View?=null
    private var toggle: ImageView?=null
    private var consoleHeader: View?=null
    private var configCard: View?=null
    private var configDetails: View?=null
    private var configArrow: ImageView?=null
    private var configBox: LinearLayout?=null
    private var configExpanded=true
    private var displayedConfig=""
    private var shownConfig: String?=null
    private var saveAction: android.view.MenuItem?=null
    private var openSwipe: MobileSwipeTile?=null
    private var listLoad: Job?=null
    private var output: TextView?=null
    private var logScroll: ScrollView?=null
    private var followLogs=true
    private var touchingLogs=false
    private var status: TextView?=null
    private var progress: ProgressBar?=null
    private var stop: MaterialButton?=null
    private var expanded=true
    private val operations=mutableListOf<View>()
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun column()=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL }
    override fun onCreateView(inflater: android.view.LayoutInflater,container: ViewGroup?,state: Bundle?): View {
        expanded=state?.getBoolean("consoleExpanded") ?: true
        configExpanded=state?.getBoolean("configExpanded") ?: true
        scroll=ScrollView(requireContext()).apply { isFillViewport=true;setBackgroundColor(MobilePalette.background) }
        body=column().apply { setPadding(dp(16),dp(8),dp(16),dp(24)) };scroll.addView(body);return scroll
    }
    override fun onViewCreated(view: View,state: Bundle?) { super.onViewCreated(view,state);build() }
    override fun onSaveInstanceState(out: Bundle) { out.putBoolean("consoleExpanded",expanded);out.putBoolean("configExpanded",configExpanded);super.onSaveInstanceState(out) }
    fun refreshSaveAction() { saveAction?.isVisible=host.hasChanges() }
    private fun disclosure(parent: LinearLayout,title: String,click: ()->Unit): ImageView {
        val header=LinearLayout(requireContext()).apply {
            gravity=android.view.Gravity.CENTER_VERTICAL;minimumHeight=dp(48)
            isFocusable=true;setOnClickListener { click() };MobileFeedback.apply(this)
        }
        header.addView(TextView(requireContext()).apply { text=title;textSize=16f;setTextColor(MobilePalette.text) },LinearLayout.LayoutParams(0,-2,1f))
        val arrow=ImageView(requireContext()).apply { imageTintList=android.content.res.ColorStateList.valueOf(MobilePalette.accent(requireContext()));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        header.addView(arrow,LinearLayout.LayoutParams(dp(26),dp(26)))
        parent.addView(header)
        return arrow
    }
    private fun heading(parent: LinearLayout,title: String) {
        parent.addView(TextView(requireContext()).apply { text=title;textSize=16f;setTextColor(MobilePalette.accent(requireContext()));setTypeface(null,android.graphics.Typeface.BOLD);setPadding(0,dp(6),0,dp(6)) })
    }
    private fun card(): LinearLayout {
        val content=column().apply { setPadding(dp(12),dp(8),dp(12),dp(8)) }
        body.addView(MaterialCardView(requireContext()).apply { radius=dp(5).toFloat();cardElevation=0f;strokeWidth=1;strokeColor=MobilePalette.outline;preventCornerOverlap=false;useCompatPadding=false;setCardBackgroundColor(MobilePalette.surface);addView(content) },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
        return content
    }
    private fun row(parent: LinearLayout,block: (LinearLayout)->Unit) {
        val r=LinearLayout(requireContext()).apply { orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL };parent.addView(r,LinearLayout.LayoutParams(-1,-2));block(r)
    }
    private fun serverTile(title: String,subtitle: String?,icon: Int,server: DeployServer?=null,click: ()->Unit) {
        val tile=LinearLayout(requireContext()).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12),dp(8),dp(12),dp(8));minimumHeight=dp(48)
            background=android.graphics.drawable.GradientDrawable().apply {
                setColor(MobilePalette.surface);cornerRadius=dp(5).toFloat();setStroke(1,MobilePalette.outline)
            }
            isFocusable=true;contentDescription=listOfNotNull(title,subtitle).joinToString(". ")
            setOnClickListener { click() };MobileFeedback.apply(this)
        }
        tile.addView(ImageView(requireContext()).apply {
            setImageResource(icon);imageTintList=android.content.res.ColorStateList.valueOf(MobilePalette.accent(requireContext()))
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(dp(26),dp(26)).apply { marginEnd=dp(12) })
        val labels=column()
        labels.addView(TextView(requireContext()).apply { text=title;textSize=16f;setTextColor(MobilePalette.text) })
        if(subtitle!=null) labels.addView(TextView(requireContext()).apply {
            text=subtitle;textSize=14f;setTextColor(MobilePalette.muted);setPadding(0,dp(3),0,0)
        })
        tile.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        val entry: View=if(server==null) tile else {
            val delete=ImageButton(requireContext()).apply {
                setImageResource(R.drawable.ic_mobile_delete);imageTintList=android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.rgb(220,66,61));setPadding(dp(18),dp(18),dp(18),dp(18))
                contentDescription="Удалить VPS из списка"
                setOnClickListener {
                    if(DeployEngine.state.value.running && DeployEngine.state.value.serverId==server.id) {
                        Toast.makeText(context,"Дождитесь завершения операции",Toast.LENGTH_SHORT).show()
                    } else MobileAlertDialogBuilder(requireContext()).setMessage("Удалить VPS из списка? Сервис на сервере останется.")
                        .setPositiveButton("Удалить") { _,_ -> DeployStore.remove(server.id);build() }.setNegativeButton(android.R.string.cancel,null).show()
                }
            }
            MobileSwipeTile(requireContext(),tile,View(requireContext()),View(requireContext()),delete,deleteOnly=true).apply {
                background=android.graphics.drawable.GradientDrawable().apply { setColor(MobilePalette.surface);cornerRadius=dp(5).toFloat();setStroke(1,MobilePalette.outline) }
                clipToOutline=true
                onOpened={ opened -> openSwipe?.takeIf { it!==opened }?.closeActions();openSwipe=opened }
            }
        }
        body.addView(entry,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
    }
    private fun button(parent: LinearLayout,title: String,icon: Int=0,pair: Boolean=false,click: ()->Unit): MaterialButton {
        val b=MaterialButton(requireContext()).apply {
            text=title;textSize=16f;setTypeface(null,android.graphics.Typeface.BOLD);isAllCaps=false;maxLines=Int.MAX_VALUE;minimumWidth=0;minimumHeight=dp(48);cornerRadius=dp(5)
            insetTop=0;insetBottom=0;elevation=0f;stateListAnimator=null
            gravity=android.view.Gravity.CENTER_VERTICAL or (if(pair) android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.START)
            setPaddingRelative(dp(12),dp(8),dp(12),dp(8))
            val accent=MobilePalette.accent(requireContext())
            val colors=android.content.res.ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(MobilePalette.muted,accent))
            setTextColor(colors);iconTint=colors
            backgroundTintList=android.content.res.ColorStateList.valueOf(MobilePalette.surface)
            strokeWidth=1
            strokeColor=android.content.res.ColorStateList.valueOf(MobilePalette.outline)
            if(icon!=0) { setIconResource(icon);iconSize=dp(22);iconPadding=dp(12);iconGravity=MaterialButton.ICON_GRAVITY_START }
            setOnClickListener { click() }
        }
        parent.addView(b,(if(pair) LinearLayout.LayoutParams(0,-2,1f) else LinearLayout.LayoutParams(-1,-2)).apply {
            topMargin=if(parent===body) 0 else dp(4)
            bottomMargin=if(parent===body) dp(8) else dp(4)
            if(pair && parent.childCount>0) marginStart=dp(8)
        })
        return b
    }
    private fun field(parent: LinearLayout,s: DeployServer,key: String,label: String,secret: Boolean=false,number: Boolean=false,pair: Boolean=false): TextInputEditText {
        val box=TextInputLayout(androidx.appcompat.view.ContextThemeWrapper(requireContext(),R.style.MobileDeployInputOverlay)).apply {
            minimumWidth=0;hint=label;boxBackgroundMode=TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(5).toFloat(),dp(5).toFloat(),dp(5).toFloat(),dp(5).toFloat())
            defaultHintTextColor=android.content.res.ColorStateList.valueOf(MobilePalette.muted)
            hintTextColor=android.content.res.ColorStateList.valueOf(MobilePalette.accent(requireContext()))
            setBoxStrokeColorStateList(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused),intArrayOf(android.R.attr.state_hovered),intArrayOf()),intArrayOf(MobilePalette.accent(requireContext()),MobilePalette.accent(requireContext()),MobilePalette.muted)))
            setEndIconTintList(android.content.res.ColorStateList.valueOf(MobilePalette.accent(requireContext())))
            if(secret) endIconMode=TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val editor=TextInputEditText(box.context).apply {
            minimumWidth=0;textSize=16f;setTextColor(MobilePalette.text);setSingleLine(true)
            inputType=if(secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else if(number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            setText(s[key]);minimumHeight=dp(48);setPadding(dp(12),dp(12),dp(12),dp(12))
            fun tintCursor() = MobilePalette.tintCursor(this)
            tintCursor()
            setOnFocusChangeListener { _,focused -> if(focused) post { tintCursor() } }
            addTextChangedListener(object: TextWatcher {
                override fun beforeTextChanged(value: CharSequence?,start: Int,count: Int,after: Int) {}
                override fun afterTextChanged(value: Editable?) {}
                override fun onTextChanged(value: CharSequence?,start: Int,before: Int,count: Int) {
                    if(s[key]==value.toString()) return
                    s[key]=value?.toString().orEmpty();if(key in listOf("host","sshPort")) s["hostKey"]=""
                    refreshSaveAction()
                }
            })
        }
        box.addView(editor,LinearLayout.LayoutParams(-1,-2))
        parent.addView(box,(if(pair) LinearLayout.LayoutParams(0,-2,1f) else LinearLayout.LayoutParams(-1,-2)).apply { topMargin=dp(6);bottomMargin=dp(6);if(pair && parent.childCount>0) marginStart=dp(8) })
        return editor
    }
    private fun switch(parent: LinearLayout,s: DeployServer,key: String,label: String,rebuild: Boolean=false) {
        parent.addView(SwitchMaterial(requireContext()).apply {
            text=label;textSize=15f;setTextColor(MobilePalette.text);minHeight=dp(48);isChecked=s[key]=="true"
            setOnCheckedChangeListener { _,checked -> s[key]=checked.toString();refreshSaveAction();if(rebuild) build() }
        },LinearLayout.LayoutParams(-1,-2))
    }
    private fun secrets(s: DeployServer) {
        if(s.kind=="csqtt" && s["password"].isBlank()) { s["password"]=CsqttDeployPolicy.password();refreshSaveAction() }
        val c=column().apply { setPadding(dp(20),dp(8),dp(20),dp(16)) }
        val passwordInput=field(c,s,"password",if(s.kind=="wdttplus") "Главный пароль" else "Пароль туннеля",secret=true)
        if(s.kind=="csqtt") button(c,"Сгенерировать пароль (16 символов)",R.drawable.ic_mobile_refresh) {
            MobileAlertDialogBuilder(requireContext()).setTitle("Сменить пароль туннеля?")
                .setMessage("Новый пароль применится при установке. Сохраните новую ссылку подключения.")
                .setPositiveButton("Сгенерировать") { _,_ ->
                    s["password"]=CsqttDeployPolicy.password()
                    passwordInput.setText(s["password"])
                    refreshSaveAction()
                }.setNegativeButton(android.R.string.cancel,null).show()
        }
        if(s.kind=="wdttplus") {
            heading(c,"DNS для клиентов (необязательно)")
            field(c,s,"dns","DNS через запятую")
        } else if(s.kind=="qwdtt") {
            heading(c,"Telegram-бот (необязательно)")
            field(c,s,"adminId","ID администратора",number=true);field(c,s,"botToken","Токен бота",secret=true)
        } else {
            heading(c,"Доступ к веб-панели")
            field(c,s,"webUser","Логин WEB");field(c,s,"webPassword","Пароль WEB",secret=true)
        }
        if(s.kind!="csqtt") { heading(c,"SSH");field(c,s,"sshPort","Порт SSH",number=true) }
        MobileAlertDialogBuilder(requireContext()).setTitle(if(s.kind=="csqtt") "Авторизация" else "Секреты деплоя")
            .setView(ScrollView(requireContext()).apply { addView(c) }).setPositiveButton("Готово",null).show()
    }
    fun build() {
        if(!this::body.isInitialized) return
        listLoad?.cancel()
        body.removeAllViews();host.toolbar.menu.clear();operations.clear();saveAction=null;openSwipe=null
        output=null;logScroll=null;status=null;progress=null;stop=null;consoleCard=null;details=null;toggle=null
        consoleHeader=null;configCard=null;configDetails=null;configArrow=null;configBox=null;shownConfig=null;displayedConfig=""
        val s=host.server
        host.toolbar.title=if(s==null) "Деплой "+DeployActivity.title(host.kind) else "Управление сервером"
        if(s==null) {
            listLoad=viewLifecycleOwner.lifecycleScope.launch {
                val result=withContext(Dispatchers.IO) { runCatching { DeployStore.savedServers().filter { it.kind==host.kind } } }
                if(host.server!=null) return@launch
                result.onSuccess { servers -> for(item in servers) {
                    serverTile(item["name"],item["host"].ifBlank { "Укажите адрес VPS" },R.drawable.ic_mobile_core,item) { host.server=item;build() }
                } }.onFailure { Toast.makeText(context,"Не удалось загрузить список VPS",Toast.LENGTH_LONG).show() }
                serverTile("Добавить VPS для деплоя",null,R.drawable.ic_mobile_add) {
                    host.server=DeployServer.create(host.kind)
                    build()
                }
            }
            return
        }
        if(s.kind=="csqtt" && s["secretPath"].isBlank()) s["secretPath"]=java.util.UUID.randomUUID().toString()
        saveAction=host.toolbar.menu.add("Сохранить").apply {
            setIcon(R.drawable.ic_mobile_apply);setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                val snapshot=DeployServer(org.json.JSONObject(s.json.toString()))
                val signature=snapshot.editSignature()
                isEnabled=false
                viewLifecycleOwner.lifecycleScope.launch {
                    val result=withContext(Dispatchers.IO) { runCatching { snapshot.validateAddress();DeployStore.save(snapshot) } }
                    if(host.server !== s) return@launch
                    saveAction?.isEnabled=true
                    result.onSuccess { Toast.makeText(context,"Сервер сохранён",Toast.LENGTH_SHORT).show();host.saved(signature) }
                        .onFailure { MobileAlertDialogBuilder(requireContext()).setMessage(it.message).setPositiveButton(android.R.string.ok,null).show() }
                }
                true
            }
        }
        refreshSaveAction()
        if(s.kind!="csqtt") {
            button(body,if(s.kind=="qwdtt") "Секреты (бот, пароли)" else "Авторизация",R.drawable.ic_baseline_vpn_key_24) { secrets(s) }
        }
        val access=card()
        if(s.kind=="csqtt") heading(access,"Деплой и SSH")
        field(access,s,"name","Название сервера")
        if(s.kind=="csqtt" && s["auth"]!="key") field(access,s,"host","IP сервера или домен")
        else { field(access,s,"host","IP / домен");field(access,s,"user","Логин SSH") }
        if(s.kind=="csqtt" && s["manualPorts"]=="true") row(access) { r ->
            field(r,s,"sshPort","SSH",number=true,pair=true)
            field(r,s,"peerPort","Туннель",number=true,pair=true)
            field(r,s,"webPort","WEB",number=true,pair=true)
        }
        if(s.kind!="csqtt") { heading(access,"Вход на сервер"); row(access) { r ->
            for((value,label) in listOf("password" to "Пароль","key" to "SSH-ключ")) button(r,label,pair=true) { s["auth"]=value;build() }.apply {
                strokeWidth=dp(1);strokeColor=android.content.res.ColorStateList.valueOf(if(s["auth"]==value) MobilePalette.accent(requireContext()) else MobilePalette.muted)
            }
        } }
        if(s["auth"]=="key") {
            button(access,if(s["key"].isBlank()) "Загрузить ключ" else "Сменить ключ",R.drawable.ic_baseline_vpn_key_24) { host.pickKey() }.apply {
                strokeWidth=dp(1);strokeColor=android.content.res.ColorStateList.valueOf(MobilePalette.accent(requireContext()))
                backgroundTintList=android.content.res.ColorStateList.valueOf(MobilePalette.background)
            }
            if(s["key"].isNotBlank()) button(access,"Удалить ключ",R.drawable.ic_mobile_delete) { s["key"]="";s["keyName"]="";build() }
            if(s["key"].isNotBlank()) heading(access,"Ключ загружен: "+s["keyName"])
            field(access,s,"keyPassword","Пароль SSH-ключа",secret=true);field(access,s,"sudoPassword","Пароль sudo (если требуется)",secret=true)
        } else if(s.kind=="csqtt") {
            field(access,s,"user","Логин SSH");field(access,s,"sshPassword","Пароль SSH",secret=true)
        } else field(access,s,"sshPassword","Пароль SSH",secret=true)
        if(s.kind=="csqtt") access.addView(SwitchMaterial(requireContext()).apply {
            text="SSH-ключи";setTextColor(MobilePalette.text);minHeight=dp(48);isChecked=s["auth"]=="key"
            setOnCheckedChangeListener { _,checked -> s["auth"]=if(checked) "key" else "password";build() }
        })
        if(s.kind=="qwdtt") { field(access,s,"dns1","DNS 1");field(access,s,"dns2","DNS 2") }
        switch(access,s,"manualPorts","Ручное управление портами",true)
        if(s.kind!="csqtt" && s["manualPorts"]=="true") {
            row(access) { r -> field(r,s,"peerPort","Порт туннеля",number=true,pair=true);field(r,s,"wgPort","Порт WG",number=true,pair=true) }
            if(s.kind=="wdttplus") field(access,s,"localPort","Локальный порт клиента",number=true)
            if(s.kind=="qwdtt") {
                row(access) { r -> field(r,s,"rawPort","Порт RAW",number=true,pair=true);field(r,s,"directPort","Прямой порт",number=true,pair=true) }
                field(access,s,"adminPort","Порт админ-панели",number=true)
            }
        }
        if(s.kind=="csqtt") {
            switch(access,s,"docker","Установить в Docker")
            button(access,"О Docker-установке") {
                MobileAlertDialogBuilder(requireContext()).setTitle("Установить в Docker")
                    .setMessage("Штатный установщик CSQTT установит Docker при необходимости и запустит сервер в контейнере. Данные и HTTPS-панель сохраняются в /etc/csqtt.")
                    .setPositiveButton(android.R.string.ok,null).show()
            }
            heading(access,"Секретный путь и ссылка панели")
            access.addView(ServerUi(requireContext()).linkRow("Адрес HTTPS-панели",s.panelUrl(),lines=3))
            button(access,"Открыть панель",R.drawable.baseline_public_24) {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(s.panelUrl())))
            }
            button(access,"Перегенерировать UUID",R.drawable.ic_mobile_refresh) {
                MobileAlertDialogBuilder(requireContext()).setTitle("Перегенерация пути")
                    .setMessage("Старый путь панели перестанет работать после следующей установки. Сохраните новый UUID.")
                    .setPositiveButton("Перегенерировать") { _,_ -> s["secretPath"]=java.util.UUID.randomUUID().toString();build() }
                    .setNegativeButton(android.R.string.cancel,null).show()
            }
            button(body,"Авторизация",R.drawable.ic_baseline_vpn_key_24) { secrets(s) }
        }
        operations+=button(body,"Установить / обновить",R.drawable.ic_mobile_core) { host.start(false) }
        operations+=button(body,"Удалить сервис с VPS",R.drawable.ic_mobile_delete) { host.start(true) }
        val console=card();consoleCard=console.parent as View
        toggle=disclosure(console,"Консоль") { expanded=!expanded;expand() };consoleHeader=toggle?.parent as? View
        val content=column();console.addView(content);details=content
        status=TextView(requireContext()).apply { setTextColor(MobilePalette.text);textSize=14f };content.addView(status)
        progress=ProgressBar(requireContext(),null,android.R.attr.progressBarStyleHorizontal);content.addView(progress,LinearLayout.LayoutParams(-1,dp(16)))
        output=TextView(requireContext()).apply { setTextColor(android.graphics.Color.rgb(134,235,149));typeface=android.graphics.Typeface.MONOSPACE;textSize=12f;setTextIsSelectable(true);setPadding(dp(12),dp(12),dp(12),dp(12)) }
        logScroll=object: ScrollView(requireContext()) {
            override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
                if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN) {
                    touchingLogs=true;followLogs=false;parent.requestDisallowInterceptTouchEvent(true)
                }
                val handled=super.dispatchTouchEvent(event)
                if(event.actionMasked==android.view.MotionEvent.ACTION_UP || event.actionMasked==android.view.MotionEvent.ACTION_CANCEL) {
                    touchingLogs=false;followLogs=!canScrollVertically(1);parent.requestDisallowInterceptTouchEvent(false)
                }
                return handled
            }
        }.apply {
            background=android.graphics.drawable.GradientDrawable().apply { setColor(android.graphics.Color.rgb(8,12,9));cornerRadius=dp(12).toFloat() };clipToOutline=true
            isVerticalScrollBarEnabled=false;isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER
            setOnScrollChangeListener { _: View, _: Int, _: Int, _: Int, _: Int ->
                if(touchingLogs) followLogs=!canScrollVertically(1)
                else if(!canScrollVertically(1)) followLogs=true
            }
            addView(output)
        }
        output?.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ ->
            if(followLogs && !touchingLogs) logScroll?.scrollTo(0,output?.height ?: 0)
        }
        content.addView(logScroll,LinearLayout.LayoutParams(-1,dp(220)))
        stop=button(content,"Остановить") { DeployEngine.cancel() }.apply {
            backgroundTintList=android.content.res.ColorStateList.valueOf(MobilePalette.background)
            insetTop=0;insetBottom=0
            (layoutParams as LinearLayout.LayoutParams).apply { topMargin=dp(12);bottomMargin=dp(6) }
        }
        val config=card();configCard=config.parent as View
        configArrow=disclosure(config,DeployActivity.title(s.kind)+" конфиг") { configExpanded=!configExpanded;expand() }
        val configBody=column();config.addView(configBody);configDetails=configBody
        // The same share blocks as every server screen: the link, its QR code and, for qWDTT, the legacy wdtt:// link.
        configBox=column().also { configBody.addView(it) }
        expand();render(DeployEngine.state.value)
    }
    private fun expand() {
        details?.visibility=if(expanded) View.VISIBLE else View.GONE
        toggle?.setImageResource(if(expanded) R.drawable.ic_baseline_expand_less_24 else R.drawable.ic_baseline_expand_more_24)
        consoleHeader?.contentDescription=if(expanded) "Консоль, раскрыта" else "Консоль, свёрнута"
        configDetails?.visibility=if(configExpanded) View.VISIBLE else View.GONE
        configArrow?.setImageResource(if(configExpanded) R.drawable.ic_baseline_expand_less_24 else R.drawable.ic_baseline_expand_more_24)
        (configArrow?.parent as? View)?.contentDescription=if(configExpanded) "Конфиг, раскрыт" else "Конфиг, свёрнут"
    }
    fun revealConsole() {
        expanded=true;followLogs=true;expand()
        (requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(scroll.windowToken,0)
        scroll.post { consoleCard?.let { scroll.smoothScrollTo(0,it.top) } }
    }
    fun render(state: DeployState) {
        val relevant=state.serverId==host.server?.id
        val s=host.server
        if(relevant && s!=null) {
            if(state.configCleared) s.recordUninstall()
            else if(state.config.isNotBlank()) s["config"]=state.config
        }
        displayedConfig=DeployConfig.display(s?.get("config").orEmpty())
        val legacy=if(s!=null && s.kind=="qwdtt" && displayedConfig.isNotBlank()) runCatching {
            app.qthrone.mobile.deploy.QwdttAdmin.links(s,s["name"].ifBlank { s["password"] },s["password"],emptyList()).second
        }.getOrNull() else null
        val shown=displayedConfig+" "+legacy.orEmpty()
        if(configBox!=null && shown!=shownConfig) {
            shownConfig=shown
            val ui=ServerUi(requireContext())
            configBox?.removeAllViews()
            if(displayedConfig.isBlank()) configBox?.addView(ui.text("Конфиг появится после успешной установки",14f,MobilePalette.muted))
            else {
                configBox?.addView(ui.linkRow(null,displayedConfig,lines=4))
                configBox?.addView(ui.qrRow(displayedConfig))
                if(legacy!=null) configBox?.addView(ui.linkRow("Ссылка WDTT (легаси)",legacy))
            }
        }
        status?.text=if(relevant) state.step+(if(state.running && !state.installerProgress && state.percent in 45..99) "" else " · "+state.percent+"%") else "Готово к подключению"
        val logs="$ "+(s?.kind ?: "deploy")+" deploy\n"+(if(relevant) state.logs.ifBlank { "Ожидание подключения к VPS…" } else "Ожидание подключения к VPS…")
        if(output?.text?.toString()!=logs) {
            output?.text=logs
            if(followLogs) logScroll?.post { if(followLogs && !touchingLogs) logScroll?.scrollTo(0,output?.height ?: 0) }
        }
        progress?.progress=if(relevant) state.percent else 0
        progress?.isIndeterminate=relevant && state.running && !state.installerProgress && state.percent in 45..99
        stop?.apply {
            isEnabled=relevant && state.running && !state.stopping
            text=if(relevant && state.stopping) "Останавливаю…" else "Остановить"
            alpha=if(isEnabled) 1f else .45f
            setTextColor(if(isEnabled) MobilePalette.accent(requireContext()) else MobilePalette.muted)
        }
        operations.forEach { it.isEnabled=!state.running }
        // Keep the encrypted record consistent with the operation's SSH/settings snapshot.
        fun editable(view: View) {
            if(view===consoleCard || view===configCard) return
            if(view is TextInputLayout || view is SwitchMaterial || view is MaterialButton) view.isEnabled=!(relevant && state.running)
            if(view is ViewGroup) for(i in 0 until view.childCount) editable(view.getChildAt(i))
        }
        if(this::body.isInitialized) editable(body)
        operations.forEach { it.isEnabled=!state.running }
    }
    override fun onDestroyView() { output=null;logScroll=null;status=null;progress=null;stop=null;consoleCard=null;details=null;toggle=null;consoleHeader=null;configCard=null;configDetails=null;configArrow=null;configBox=null;shownConfig=null;saveAction=null;openSwipe=null;operations.clear();super.onDestroyView() }
}

