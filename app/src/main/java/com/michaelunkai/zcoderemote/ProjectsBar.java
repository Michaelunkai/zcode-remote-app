package com.michaelunkai.zcoderemote;

import android.webkit.WebView;

/**
 * Projects quick-bar (v1.8.0): a small floating pill injected into the live
 * session page that opens a panel with (a) a top "Add / Edit project" button
 * that drives the web app's own Projects menu, and (b) one "Archive all"
 * action per project so every session under that project can be archived
 * with a single tap — on the phone today, and the identical script is shipped
 * in assets/projects-bar.js for pasting into a PC browser on the same page.
 *
 * The bar is self-reporting: every scan of the page's visible menu structure
 * is sent to ZcodeAndroid.reportScan (and logcat) so the automation can be
 * verified — and its selectors tuned — without ever printing the session URL.
 */
public final class ProjectsBar {

    public static final String TAG = "ZcodeProjectsBar";

    private ProjectsBar() {
    }

    /** Injects the bar (idempotent) into an already-loaded session page. */
    public static void inject(WebView webView) {
        if (webView == null) {
            return;
        }
        webView.evaluateJavascript(JS, null);
    }

    static final String JS =
        "(function(){"
        + "if(window.__zcProjectsBar)return;window.__zcProjectsBar=true;"
        + "function visible(e){var r=e.getBoundingClientRect();return r.width>4&&r.height>4;}"
        + "function txt(e){return ((e.innerText||'')||(e.textContent||'')).trim();}"
        + "function cls(e){var c=e.className;return (typeof c==='string'?c:(c&&c.baseVal)||'');}"
        + "function scan(){"
        + "var out=[];"
        + "var sel='button,[role=button],[role=menuitem],[role=tab],[role=listitem],a,li,[aria-label]';"
        + "var all=document.querySelectorAll(sel);"
        + "for(var i=0;i<all.length&&out.length<400;i++){var e=all[i];"
        + "if(!visible(e))continue;"
        + "var t=txt(e);if(t.length>90)t=t.slice(0,90);"
        + "var al=e.getAttribute('aria-label')||'';"
        + "if(!t&&!al)continue;"
        + "var r=e.getBoundingClientRect();"
        + "out.push({tag:e.tagName,role:e.getAttribute('role')||'',aria:al,t:t,"
        + "c:cls(e).slice(0,60),x:Math.round(r.left),y:Math.round(r.top),w:Math.round(r.width),h:Math.round(r.height)});}"
        + "return out;}"
        + "function report(label){"
        + "try{var d={label:label,ts:Date.now(),url:host(),items:scan(),"
        + "body:((document.body&&document.body.innerText)||'').slice(0,1500)};"
        + "var s=JSON.stringify(d);"
        + "if(window.ZcodeAndroid&&window.ZcodeAndroid.reportScan)window.ZcodeAndroid.reportScan(s);"
        + "console.log('ZCPB '+s);}catch(e){}}"
        + "function host(){try{return location.host;}catch(e){return '';}}"
        + "function click(e){if(!e)return false;"
        + "var r=e.getBoundingClientRect();"
        + "var ev=new MouseEvent('pointerdown',{bubbles:true});e.dispatchEvent(ev);"
        + "e.dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));"
        + "e.dispatchEvent(new PointerEvent('pointerup',{bubbles:true}));"
        + "e.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));"
        + "e.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true}));"
        + "if(typeof e.click==='function')try{e.click();}catch(x){}"
        + "return true;}"
        + "function byText(re,maxLen){var all=document.querySelectorAll('button,[role=button],[role=menuitem],a,li,[role=tab],div[onclick]');"
        + "for(var i=0;i<all.length;i++){var e=all[i];if(!visible(e))continue;"
        + "var t=txt(e);if(maxLen&&t.length>maxLen)continue;"
        + "var al=e.getAttribute('aria-label')||'';"
        + "if(re.test(t)||re.test(al))return e;}return null;}"
        + "function openDrawer(){"
        + "var h=document.querySelector(\"[aria-label*='menu' i],[aria-label*='Menu' i],[aria-label*='navigation' i]\");"
        + "if(h&&visible(h))click(h);"
        + "return !!h;}"
        + "function openProjects(){openDrawer();"
        + "setTimeout(function(){var p=byText(/^projects?$/i,20);"
        + "if(p)click(p);setTimeout(function(){report('projects-open');},1200);},600);}"
        // ---- panel UI ----
        + "var bar=document.createElement('div');"
        + "bar.style.cssText='position:fixed;top:6px;right:6px;z-index:2147483646;"
        + "background:#1b1b2f;color:#fff;border:1px solid #8B7CFF;border-radius:20px;"
        + "padding:6px 12px;font:600 13px system-ui;box-shadow:0 2px 8px rgba(0,0,0,.5);"
        + "cursor:pointer;user-select:none;';bar.textContent='Projects';"
        + "var panel=document.createElement('div');"
        + "panel.style.cssText='position:fixed;top:44px;right:6px;z-index:2147483647;display:none;"
        + "background:#15152a;color:#fff;border:1px solid #8B7CFF;border-radius:12px;"
        + "padding:10px;font:13px system-ui;width:270px;max-height:70vh;overflow:auto;"
        + "box-shadow:0 4px 16px rgba(0,0,0,.6);';"
        + "var addBtn=document.createElement('button');"
        + "addBtn.style.cssText='display:block;width:100%;margin-bottom:8px;padding:8px;"
        + "background:#8B7CFF;color:#fff;border:0;border-radius:8px;font:600 13px system-ui;cursor:pointer;';"
        + "addBtn.textContent='+ Add / Edit project';"
        + "addBtn.addEventListener('click',function(){openProjects();"
        + "setStatus('opening the app Projects menu…');});"
        + "var scanBtn=document.createElement('button');"
        + "scanBtn.style.cssText='display:block;width:100%;margin-bottom:8px;padding:6px;"
        + "background:#2a2a4a;color:#fff;border:1px solid #444;border-radius:8px;cursor:pointer;';"
        + "scanBtn.textContent='Refresh project list';"
        + "var status=document.createElement('div');"
        + "status.style.cssText='font:12px system-ui;color:#aaa;margin:4px 0 8px;min-height:14px;';"
        + "var list=document.createElement('div');"
        + "panel.appendChild(addBtn);panel.appendChild(scanBtn);"
        + "panel.appendChild(status);panel.appendChild(list);"
        + "function setStatus(s){status.textContent=s;}"
        + "function findProjectsSection(){"
        + "var items=scan();var found=[];"
        + "for(var i=0;i<items.length;i++){var it=items[i];"
        + "if(/project/i.test(it.aria)||(it.t&&it.t.length<60&&!/^(projects?)$/i.test(it.t)&&nearProjects(it))){"
        + "found.push(it);}}"
        + "return found;}"
        + "var projectsSeen=false;"
        + "function nearProjects(it){return projectsSeen&&it.y>60;}"
        + "function renderProjects(){"
        + "list.innerHTML='';"
        + "var names=collectProjectNames();"
        + "if(!names.length){setStatus('Open the drawer (Projects) once, then tap refresh.');return;}"
        + "for(var i=0;i<names.length;i++){(function(name){"
        + "var row=document.createElement('div');"
        + "row.style.cssText='display:flex;align-items:center;justify-content:space-between;"
        + "padding:6px 2px;border-bottom:1px solid #2a2a4a;gap:6px;';"
        + "var lab=document.createElement('span');"
        + "lab.style.cssText='overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:150px;';"
        + "lab.textContent=name;"
        + "var b=document.createElement('button');"
        + "b.style.cssText='background:#7a3b3b;color:#fff;border:0;border-radius:6px;"
        + "padding:5px 8px;font:600 11px system-ui;cursor:pointer;white-space:nowrap;';"
        + "b.textContent='Archive all';"
        + "b.addEventListener('click',function(){archiveAll(name);});"
        + "row.appendChild(lab);row.appendChild(b);list.appendChild(row);})(names[i]);}}"
        + "function collectProjectNames(){"
        // heuristic: short clickable items listed right after a 'Projects' heading
        + "var els=document.querySelectorAll('button,[role=button],[role=menuitem],[role=listitem],a,li');"
        + "var names=[];var inProjects=false;"
        + "for(var i=0;i<els.length;i++){var e=els[i];if(!visible(e))continue;"
        + "var t=txt(e);"
        + "if(/^projects?$/i.test(t)){inProjects=true;continue;}"
        + "if(inProjects){"
        + "if(/^(task list|new task|automations|plugin marketplace|pinned tasks|settings|sessions?)$/i.test(t)){inProjects=false;continue;}"
        + "if(t&&t.length<60&&names.indexOf(t)<0)names.push(t);}}"
        + "return names.slice(0,30);}"
        + "function sleep(ms){return new Promise(function(res){setTimeout(res,ms);});}"
        + "async function archiveAll(projectName){"
        + "setStatus('Archiving sessions in \"'+projectName+'\"…');"
        + "var archived=0,guard=0;"
        + "while(guard++<50){"
        + "var rowEl=null;"
        + "var els=document.querySelectorAll('button,[role=button],[role=menuitem],[role=listitem],a,li,[role=row]');"
        + "for(var i=0;i<els.length;i++){var e=els[i];"
        + "if(visible(e)&&txt(e)===projectName){rowEl=e;break;}}"
        + "if(!rowEl){setStatus('Project row not found (archived '+archived+').');return;}"
        + "click(rowEl);await sleep(900);report('project-open');"
        // find the session row's overflow menu ("..." / more)
        + "var menu=byText(/^\\.{3}$|^…$|more|menu/i,20);"
        + "var item=null;"
        + "if(menu){click(menu);await sleep(500);report('row-menu');"
        + "item=byText(/archive/i,40);}"
        + "if(!item){item=byText(/archive/i,40);}"
        + "if(!item){setStatus('No Archive action found (archived '+archived+').');return;}"
        + "click(item);await sleep(500);"
        + "var conf=byText(/^(archive|confirm|yes|ok)$/i,20);"
        + "if(conf){click(conf);await sleep(700);}"
        + "archived++;setStatus('Archived '+archived+' session(s)…');"
        + "report('archived-'+archived);"
        // if the SPA left the project view, go back in
        + "if(!findExact(projectName)){click(rowEl)||openProjects();await sleep(900);}}"
        + "setStatus('Done: '+archived+' session(s) archived in \"'+projectName+'\".');}"
        + "function findExact(name){var els=document.querySelectorAll('button,[role=button],[role=menuitem],[role=listitem],a,li,[role=row]');"
        + "for(var i=0;i<els.length;i++){if(visible(els[i])&&txt(els[i])===name)return els[i];}return null;}"
        + "scanBtn.addEventListener('click',function(){report('manual');projectsSeen=true;renderProjects();"
        + "var n=collectProjectNames();setStatus(n.length+' project(s) found.');});"
        + "bar.addEventListener('click',function(){"
        + "var show=panel.style.display==='none';panel.style.display=show?'block':'none';"
        + "if(show){projectsSeen=true;renderProjects();report('panel-open');}});"
        + "function mount(){if(!document.body)return;document.body.appendChild(bar);document.body.appendChild(panel);}"
        + "if(document.body)mount();else document.addEventListener('DOMContentLoaded',mount);"
        + "setInterval(function(){report('periodic');},20000);"
        + "setTimeout(function(){report('initial');},2500);"
        + "})();";
}
