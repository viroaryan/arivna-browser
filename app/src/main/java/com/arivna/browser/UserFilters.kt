package com.arivna.browser

import android.content.Context
import org.json.JSONObject
import java.io.File

/** A page element the user tapped in the element picker. */
class PickedElement(val host: String, val selector: String, val tab: Tab)

/**
 * The user's own element-hiding rules, kept in `filesDir/filters/my-filters.txt` as
 * uBO-style `host##selector` lines. The file is compiled into the engine as a trusted list.
 */
object UserFilters {
    const val NAME = "my-filters"
    private const val HEADER = "! Arivna user filters. One element-hiding rule per line: host##selector\n"

    private fun file(ctx: Context): File = File(File(ctx.filesDir, "filters"), "$NAME.txt")

    /** Rules in file order. Comments and blank lines are skipped. */
    fun rules(ctx: Context): List<String> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("!") }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Builds the rule line for a picked element, or null when the input isn't usable. */
    fun ruleFor(host: String, selector: String): String? {
        val sel = selector.trim()
        val h = host.trim().lowercase().removePrefix("www.").ifEmpty { return null }
        if (sel.isEmpty() || sel.length > 500 || sel.any { it == '\n' || it == '\r' || it == '{' || it == '}' }) return null
        if (h.any { it == '/' || it == '|' || it == '$' || it == '=' || it == '#' || it.isWhitespace() }) return null
        return "$h##$sel"
    }

    /** Adds a rule. Returns false when it is invalid or already present. */
    fun add(ctx: Context, host: String, selector: String): Boolean {
        val line = ruleFor(host, selector) ?: return false
        val existing = rules(ctx)
        if (line in existing) return false
        val text = buildString {
            append(HEADER)
            (existing + line).forEach { append(it).append('\n') }
        }
        file(ctx).parentFile?.mkdirs()
        writeAtomic(file(ctx), text)
        return true
    }

    fun remove(ctx: Context, line: String) {
        val kept = rules(ctx).filter { it != line }
        val text = buildString {
            append(HEADER)
            kept.forEach { append(it).append('\n') }
        }
        writeAtomic(file(ctx), text)
    }

    /** JS that hides the rule's elements on the current page straight away, before the engine reloads. */
    fun hideNowScript(selector: String): String =
        "(function(){try{document.querySelectorAll(${JSONObject.quote(selector)}).forEach(function(e){" +
            "e.style.setProperty('display','none','important');});}catch(e){}})();"
}

/**
 * Element picker injected into the page. A tap highlights the element, builds a selector
 * (id, or the shortest class/tag path that matches only that element) and reports it through
 * `__arivnaShield.pick`, while the tap itself is swallowed so links don't navigate.
 */
object ElementPicker {
    const val START = """(function(){
if(window.__arivnaPicker)return;window.__arivnaPicker=true;
var prev=null,prevOutline='',prevOffset='';
var IDENT=/^[a-zA-Z][\w-]*$/;
function esc(s){return (window.CSS&&CSS.escape)?CSS.escape(s):s.replace(/([^\w-])/g,'\\$1');}
function only(s,el){try{var l=document.querySelectorAll(s);return l.length===1&&l[0]===el;}catch(e){return false;}}
function stepOf(el){
  var s=el.tagName.toLowerCase();
  var cs=Array.prototype.filter.call(el.classList||[],function(c){return IDENT.test(c)&&!/\d{4,}/.test(c);}).slice(0,2);
  if(cs.length)s+='.'+cs.map(esc).join('.');
  return s;
}
function selectorFor(el){
  if(el.id&&IDENT.test(el.id)&&only('#'+esc(el.id),el))return '#'+esc(el.id);
  var steps=[],cur=el;
  for(var d=0;cur&&cur.nodeType===1&&cur!==document.body&&cur!==document.documentElement&&d<8;d++){
    var step=stepOf(cur);
    if(cur.parentElement){
      var sib=Array.prototype.filter.call(cur.parentElement.children,function(c){return c.tagName===cur.tagName;});
      if(sib.length>1)step+=':nth-of-type('+(sib.indexOf(cur)+1)+')';
    }
    steps.unshift(step);
    var s=steps.join(' > ');
    if(only(s,el))return s;
    cur=cur.parentElement;
  }
  return steps.join(' > ')||el.tagName.toLowerCase();
}
function mark(el){
  if(prev===el)return;
  if(prev){prev.style.outline=prevOutline;prev.style.outlineOffset=prevOffset;}
  prevOutline=el.style.outline;prevOffset=el.style.outlineOffset;prev=el;
  el.style.outline='3px solid #e5484d';el.style.outlineOffset='-3px';
}
function onClick(e){
  e.preventDefault();e.stopPropagation();e.stopImmediatePropagation();
  var el=e.target;
  if(!el||el.nodeType!==1||el===document.body||el===document.documentElement)return;
  mark(el);
  if(window.__arivnaPick)window.__arivnaPick(selectorFor(el));
}
window.__arivnaPickerStop=function(){
  document.removeEventListener('click',onClick,true);
  if(prev){prev.style.outline=prevOutline;prev.style.outlineOffset=prevOffset;prev=null;}
  window.__arivnaPicker=false;
};
document.addEventListener('click',onClick,true);
})();"""

    const val STOP = "window.__arivnaPickerStop&&window.__arivnaPickerStop();"
}
