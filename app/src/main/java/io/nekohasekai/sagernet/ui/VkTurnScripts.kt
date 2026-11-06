// Adapted from SpaceNeuroX/proxy-turn-vk-android a296c57 (GPL-3.0).
package app.rawline.mobile.ui

internal object VkTurnScripts {
    fun joinUrlCandidates(hash: String): List<String> = listOf(
        "https://m.vk.ru/call/join/$hash",
        "https://vk.ru/call/join/$hash",
        "https://m.vk.com/call/join/$hash",
        "https://vk.com/call/join/$hash",
    )

    const val INTERCEPTOR = """
        (function() {
            if (window.__wdtt_vk_auth_installed) return;
            window.__wdtt_vk_auth_installed = true;

            function tryEmitTurnServer(data) {
                if (!data) return;
                var ts = data.turn_server;
                if (!ts && data.response && data.response.turn_server) {
                    ts = data.response.turn_server;
                }
                if (ts && ts.username && ts.credential && ts.urls) {
                    window.__qthroneTurnServer=JSON.stringify(ts);
                    if(window.QThroneVkTurn) window.QThroneVkTurn.postMessage(JSON.stringify(ts));
                }
            }

            const origFetch = window.fetch;
            window.fetch = async function() {
                const response = await origFetch.apply(this, arguments);
                try {
                    const clone = response.clone();
                    const text = await clone.text();
                    if (text && text.indexOf('turn_server') !== -1) {
                        tryEmitTurnServer(JSON.parse(text));
                    }
                } catch(e) {}
                return response;
            };

            const origXHROpen = XMLHttpRequest.prototype.open;
            const origXHRSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(method, url) {
                this._wdtt_url = url;
                return origXHROpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function() {
                const xhr = this;
                xhr.addEventListener('load', function() {
                    try {
                        if (!xhr.responseText || xhr.responseText.indexOf('turn_server') === -1) return;
                        tryEmitTurnServer(JSON.parse(xhr.responseText));
                    } catch(e) {}
                });
                return origXHRSend.apply(this, arguments);
            };
        })();
    """

    const val AUTO_JOIN_SETUP = """
        (function() {
            // Всегда переустанавливаем хелперы: после bfcache/SPA ready мог остаться true,
            // а __wdtt_autoJoinTry — устаревшим → тихий режим «кнопка не найдена» со 2-го раза.
            window.__wdtt_auto_join_ready = true;
            window.__wdtt_auto_join_clicks = window.__wdtt_auto_join_clicks || 0;
            window.__wdtt_auto_join_done = false;

            var rejectPhrases = [
                'открыть в приложении',
                'open in app',
                'open in the',
                'vk звонки',
                'скачать приложение'
            ];

            function norm(s) {
                return (s || '').replace(/\s+/g, ' ').trim().toLowerCase();
            }

            function elementText(el) {
                return norm(el.innerText || el.textContent || el.value || el.getAttribute('aria-label') || '');
            }

            function scoreElement(el) {
                var text = elementText(el);
                if (!text || text.length > 120) return -1;
                for (var r = 0; r < rejectPhrases.length; r++) {
                    if (text.indexOf(rejectPhrases[r]) !== -1) return -1;
                }
                if (text.indexOf('продолжить в браузере') !== -1 && text.indexOf('открыть') !== -1 && text.length > 30) {
                    return -1;
                }
                if (text === 'продолжить в браузере' || text === 'continue in browser') return 100;
                if (text.indexOf('продолжить в браузере') !== -1) return 90 - Math.min(text.length, 80);
                if (text.indexOf('continue in browser') !== -1) return 85 - Math.min(text.length, 80);
                // Актуальная кнопка на m.vk: «Присоединиться»
                if (text === 'присоединиться' || text === 'join') return 95;
                if (text.indexOf('присоединиться к звонку через браузер') !== -1) return 70;
                if (text.indexOf('присоединиться через браузер') !== -1) return 65;
                if (text.indexOf('присоединиться к звонку') !== -1) return 60;
                if (text.indexOf('присоединиться') !== -1 && text.length <= 40) return 55;
                if (text.indexOf('войти в звонок') !== -1) return 50;
                if (text === 'продолжить' || text === 'continue') return 40;
                if (text.indexOf('продолжить') !== -1 && text.length <= 25) return 35;
                return -1;
            }

            function hasBetterChild(el, parentScore) {
                var kids = el.querySelectorAll('button, a, [role="button"], input[type="button"], input[type="submit"]');
                for (var i = 0; i < kids.length; i++) {
                    if (kids[i] === el) continue;
                    var cs = scoreElement(kids[i]);
                    if (cs >= parentScore) return true;
                }
                return false;
            }

            function pickBest(minScore) {
                var selectors = 'button, a, [role="button"], input[type="button"], input[type="submit"], .vkuiButton, [class*="Button"]';
                var nodes = document.querySelectorAll(selectors);
                var best = null;
                var bestScore = -1;
                var bestLen = 9999;
                for (var i = 0; i < nodes.length; i++) {
                    var el = nodes[i];
                    var sc = scoreElement(el);
                    if (sc < minScore) continue;
                    if (hasBetterChild(el, sc)) continue;
                    var tlen = elementText(el).length;
                    if (sc > bestScore || (sc === bestScore && tlen < bestLen)) {
                        best = el;
                        bestScore = sc;
                        bestLen = tlen;
                    }
                }
                return best ? { el: best, score: bestScore, text: elementText(best) } : null;
            }

            function fireClick(el) {
                try { el.scrollIntoView({block:'center', inline:'nearest'}); } catch (e0) {}
                try { el.click(); } catch (e1) {}
                try {
                    el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                } catch (e2) {}
            }

            window.__wdtt_autoJoinTry = function() {
                // Не блокируем навсегда: клик мог попасть в пустоту / SPA ещё не готова.
                if (window.__wdtt_auto_join_done) return '';
                var pick = pickBest(50) || pickBest(35);
                if (!pick) return '';
                fireClick(pick.el);
                window.__wdtt_auto_join_clicks = (window.__wdtt_auto_join_clicks || 0) + 1;
                window.__wdtt_auto_join_done = true;
                setTimeout(function() { window.__wdtt_auto_join_done = false; }, 2200);
                return 'clicked(score=' + pick.score + '):' + pick.text.substring(0, 60);
            };

            try {
                if (window.__wdtt_auto_join_observer) {
                    window.__wdtt_auto_join_observer.disconnect();
                }
            } catch (e) {}
            window.__wdtt_auto_join_observer = new MutationObserver(function() {
                try { window.__wdtt_autoJoinTry(); } catch (e) {}
            });
            var root = document.documentElement || document.body;
            if (root) {
                window.__wdtt_auto_join_observer.observe(root, { childList: true, subtree: true });
            }
            setTimeout(function() {
                try { window.__wdtt_auto_join_observer && window.__wdtt_auto_join_observer.disconnect(); } catch(e) {}
            }, 45000);
            return 'ready';
        })();
    """

    const val AUTO_JOIN_TRY =
        "(function(){ return (window.__wdtt_autoJoinTry && window.__wdtt_autoJoinTry()) || ''; })();"

    const val AUTO_JOIN_RESET = """
        (function(){
            try { if (window.__wdtt_auto_join_observer) window.__wdtt_auto_join_observer.disconnect(); } catch(e) {}
            window.__wdtt_auto_join_observer = null;
            window.__wdtt_auto_join_clicks = 0;
            window.__wdtt_auto_join_ready = false;
            window.__wdtt_auto_join_done = false;
            window.__wdtt_autoJoinTry = null;
        })();
    """

    const val PAGE_404_CHECK =
        "(function(){var t=document.body?document.body.innerText:'';return t.indexOf('Такой страницы нет')!==-1||t.indexOf('Страница не найдена')!==-1?'404':'';})();"
}
