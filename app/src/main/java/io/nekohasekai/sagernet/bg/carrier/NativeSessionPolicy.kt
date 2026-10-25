package app.qthrone.mobile.bg.carrier

import app.qthrone.mobile.outbound.json.JsonInput

internal object NativeSessionPolicy {
    // The CSQTT wrapper waits for Rust's VK creator/bot leave operations before it exits.
    fun shutdownGraceMs(kind: CarrierKind) = when(kind) { CarrierKind.CSQTT -> 20_000L; CarrierKind.SSTP -> 3_000L; else -> 1_000L }
    fun probeConfig(kind:CarrierKind,privateJson:String,interactive:Boolean,latencyProbe:Boolean=!interactive):String {
        if(kind == CarrierKind.WDTTPLUS) {
            if(!latencyProbe) return privateJson
            return JsonInput.parseObject(privateJson).also {
                val args=it.array("args").strings().toMutableList()
                val count=args.indexOf("-n")
                if(count >= 0 && count+1 < args.size) args[count+1]="9"
                it["args"]=app.qthrone.mobile.outbound.json.JsonArray.of(*args.toTypedArray())
            }.toCompact()
        }
        if((interactive && !latencyProbe) || kind !in listOf(CarrierKind.QWDTT,CarrierKind.CSQTT)) return privateJson
        return JsonInput.parseObject(privateJson).also {
            // Native auto captcha is allowed, visible prompts are refused by the Android bridge.
            it["probe_only"]=true;it["interactive_captcha"]=true
            if(kind==CarrierKind.QWDTT && latencyProbe) it["workers"]=9
        }.toCompact()
    }

    fun startupTimeoutMs(kind:CarrierKind,requested:Long):Long = when(kind) {
        CarrierKind.QWDTT,CarrierKind.CSQTT,CarrierKind.WDTTPLUS -> requested.coerceAtLeast(300_000L)
        // DNS tunnels measure MTU and handshake query by query; S3 Bypass waits for the bucket.
        CarrierKind.DNSTT,CarrierKind.VAYDNS,CarrierKind.SLIPSTREAM,CarrierKind.MASTERDNS,CarrierKind.COTTENDNS,CarrierKind.SOAR,CarrierKind.S3BYPASS,CarrierKind.SSTP -> requested.coerceAtLeast(120_000L)
        else -> requested
    }
    private val callErrorCode=Regex("error_code[\\s]*[:=][\\s]*[\"']?([0-9]+)",RegexOption.IGNORE_CASE)
    fun unavailableCall(line:String):Boolean {
        val code=callErrorCode.find(line)?.groupValues?.get(1)?.toIntOrNull()
        return line.contains("Call not found",ignoreCase=true) || code==951 || code==954 || (code!=null && code in 9000..9999)
    }
    fun rotatedHashes(privateJson:String,attempt:Int):String {
        if(attempt==0) return privateJson
        val config=JsonInput.parseObject(privateJson)
        val hashes=config.array("hashes").strings()
        if(hashes.isEmpty()) return privateJson
        val offset=attempt%hashes.size
        config["hashes"]=app.qthrone.mobile.outbound.json.JsonArray().also { array ->
            (hashes.drop(offset)+hashes.take(offset)).forEach { array.add(it) }
        }
        return config.toCompact()
    }

    fun sharesRemoteSession(firstKind:CarrierKind,first:String,secondKind:CarrierKind,second:String):Boolean {
        if(firstKind==CarrierKind.OPENFLUX && secondKind==CarrierKind.OPENFLUX) {
            // An OpenFlux exit serves one client at a time: a second one on any shared carrier takes the session over.
            fun carriers(json:String)=JsonInput.parseObject(json).array("transports")
                .filterIsInstance<app.qthrone.mobile.outbound.json.JsonObject>()
                .map { it.string("type")+" "+it.string(if(it.string("type")=="direct") "dial" else "url").trim() }.toSet()
            return carriers(first).intersect(carriers(second)).isNotEmpty()
        }
        if(firstKind==CarrierKind.SSTP && secondKind==CarrierKind.SSTP) {
            val a=JsonInput.parseObject(first);val b=JsonInput.parseObject(second)
            return a.string("server").isNotBlank() && a.string("server")==b.string("server") &&
                a.int("server_port")==b.int("server_port") && a.string("username")==b.string("username")
        }
        if(firstKind!=secondKind || firstKind !in listOf(CarrierKind.QWDTT,CarrierKind.CSQTT,CarrierKind.WDTTPLUS)) return false
        val a=JsonInput.parseObject(first);val b=JsonInput.parseObject(second)
        if(firstKind==CarrierKind.CSQTT) return a.string("peer").isNotBlank() && a.string("password").isNotEmpty() &&
            a.string("peer")==b.string("peer") && a.string("password")==b.string("password")
        if(firstKind == CarrierKind.WDTTPLUS) {
            fun value(json: app.qthrone.mobile.outbound.json.JsonObject, flag: String): String {
                val args=json.array("args").strings(); val index=args.indexOf(flag)
                return if(index >= 0) args.getOrNull(index+1).orEmpty() else ""
            }
            val peer=value(a,"-peer"); val device=value(a,"-device-id")
            return peer.isNotBlank() && device.isNotBlank() && peer == value(b,"-peer") && device == value(b,"-device-id")
        }
        return a.string("peer").isNotEmpty() && a.string("device_id").isNotEmpty() &&
            a.string("peer")==b.string("peer") && a.string("device_id")==b.string("device_id")
    }
    fun startupFailure(line:String):String? = when {
        line.contains("SSTP_AUTH_FAILED") -> "Сервер SSTP отклонил авторизацию: проверьте имя пользователя и пароль"
        line.contains("SSTP_TLS_FAILED") -> "Ошибка TLS SSTP: проверьте сертификат сервера и SNI"
        line.contains("FATAL_SSTP:") -> "Ошибка SSTP: " + line.substringAfter("FATAL_SSTP:").trim()
        line.contains("SSTP connection failed:") -> "Ошибка SSTP: " + line.substringAfter("SSTP connection failed:").trim()
        line.contains("SSTP configuration error:") -> "Ошибка SSTP: " + line.substringAfter("SSTP configuration error:").trim()
        line.contains("SSTP virtualNet error:") -> "Ошибка SSTP: " + line.substringAfter("SSTP virtualNet error:").trim()
        line.contains("FATAL_AUTH:") || line.contains("Сервер отказал в авторизации CSQTT") ||
            line.contains("Пароль CSQTT привязан к другому устройству") -> "Сервер отклонил авторизацию: проверьте пароль, срок доступа и привязку устройства"
        line.contains("FATAL_PROTOCOL:") || line.contains("Клиент и сервер CSQTT несовместимы") -> "Сервер отклонил несовместимый протокол подключения"
        else -> null
    }
}
