package com.glance.remote

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * The panel's only script: it adds and removes scheduled content rows and keeps their form field
 * names contiguous. It is inlined and allow-listed by hash, so the Content-Security-Policy never
 * has to fall back to 'unsafe-inline'.
 *
 * Without the script the page degrades rather than breaks: rows rendered by the tablet already
 * carry valid field names and still save, and the page always renders at least one row, so a
 * single profile can be created. Only adding further rows and removing existing ones need it.
 *
 * Keep [SOURCE] on a single line: the surrounding page template calls trimIndent(), which would
 * otherwise rewrite the script and invalidate [CSP_SOURCE].
 */
internal object RemotePanelScript {
    const val SOURCE = "(function(){var l=document.getElementById('content-profiles');if(!l)" +
        "return;var t=document.getElementById('content-profile-template');var a=" +
        "document.getElementById('add-content-profile');function r(){var rows=" +
        "l.querySelectorAll('.profile-row');for(var i=0;i<rows.length;i++){var f=" +
        "rows[i].querySelectorAll('[data-name]');for(var j=0;j<f.length;j++){f[j].name=" +
        "'profile.'+i+'.'+f[j].getAttribute('data-name');}}var e=" +
        "document.getElementById('content-profiles-empty');if(e)e.hidden=rows.length>0;}" +
        "l.addEventListener('click',function(ev){var b=ev.target.closest('.profile-remove');" +
        "if(!b)return;ev.preventDefault();var row=b.closest('.profile-row');if(row){" +
        "row.parentNode.removeChild(row);r();}});if(a&&t)a.addEventListener('click'," +
        "function(ev){ev.preventDefault();l.appendChild(t.content.cloneNode(true));r();});r();})();"

    /** Source expression for the CSP script-src directive, including the quotes it requires. */
    val CSP_SOURCE: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(SOURCE.toByteArray(StandardCharsets.UTF_8))
        "'sha256-${Base64.getEncoder().encodeToString(digest)}'"
    }
}
