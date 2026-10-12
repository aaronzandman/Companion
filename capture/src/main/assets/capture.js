/* Portal Capture: runs in every page and frame of the portal you open.
   It lists field labels, names, types and buttons, and notes which buttons you tap.
   It never reads what is typed into any field (including passwords) and never sends anything over the internet:
   it only hands the list to the Portal Capture app on this phone. */
(function(){
  if (window.__r2cap) return; window.__r2cap = 1;
  var red=function(t){ return String(t||"").replace(/\s+/g," ").trim().slice(0,120).replace(/[^\s@]+@[^\s@]+\.[a-z]{2,}/gi,"[email]").replace(/\d{6,}/g,function(m){return "#".repeat(m.length);}); };
  var vis=function(el){ try{ var r=el.getBoundingClientRect(), cs=getComputedStyle(el); return r.width>0&&r.height>0&&cs.visibility!=="hidden"&&cs.display!=="none"; }catch(e){ return false; } };
  var labelOf=function(el){
    var t="", doc=el.ownerDocument;
    if(el.id){ try{ var l=doc.querySelector('label[for="'+CSS.escape(el.id)+'"]'); if(l) t=l.textContent; }catch(e){} }
    if(!t){ var p=el.closest("label"); if(p) t=p.textContent; }
    if(!t&&el.getAttribute("aria-labelledby")){ t=el.getAttribute("aria-labelledby").split(/\s+/).map(function(i){ var x=doc.getElementById(i); return x?x.textContent:""; }).join(" "); }
    if(!t) t=el.getAttribute("aria-label")||"";
    if(!t){ var c=el.closest("td,th,div"); var prev=c&&c.previousElementSibling; if(prev&&prev.textContent.trim().length<80) t=prev.textContent; }
    if(!t) t=el.getAttribute("placeholder")||"";
    return red(t);
  };
  var sectionOf=function(el){ var n=el, i=0; while(n&&i<400){ var s=n.previousElementSibling; while(s){ var h=s.matches&&s.matches("h1,h2,h3,h4,legend")?s:(s.querySelector&&s.querySelector("h1,h2,h3,h4,legend")); if(h) return red(h.textContent); s=s.previousElementSibling; i++; } n=n.parentElement; } return ""; };
  var FIELDS="input,select,textarea,[contenteditable=true],[role=combobox],[role=textbox],[role=checkbox],[role=radio],[role=listbox],[role=spinbutton]";
  var BUTTONS="button,input[type=submit],input[type=button],input[type=image],a[role=button],[role=button],[role=tab],[role=menuitem]";
  function scan(){
    var out={site:location.hostname,path:location.pathname,title:red(document.title),top:window===window.top,fields:[],buttons:[]};
    document.querySelectorAll(FIELDS).forEach(function(el){
      var type=(el.getAttribute("type")||el.getAttribute("role")||el.tagName).toLowerCase();
      if(type==="hidden"){ out.fields.push({kind:"hidden",name:red(el.name),id:red(el.id)}); return; }
      var f={kind:el.tagName.toLowerCase(),type:type,name:red(el.name),id:red(el.id),label:labelOf(el),section:sectionOf(el),
        placeholder:red(el.getAttribute("placeholder")),required:!!(el.required||el.getAttribute("aria-required")==="true"),
        visible:vis(el),maxlength:el.getAttribute("maxlength")||"",pattern:red(el.getAttribute("pattern")),readonly:!!el.readOnly,disabled:!!el.disabled,
        inputmode:el.getAttribute("inputmode")||"",autocomplete:el.getAttribute("autocomplete")||""};
      if(type==="password") f.note="password field (nothing read)";
      if(el.tagName==="SELECT") f.options=Array.prototype.slice.call(el.options,0,60).map(function(o){ return red(o.textContent); });
      out.fields.push(f);
    });
    document.querySelectorAll(BUTTONS).forEach(function(b){
      var t=red(b.innerText||b.value||b.getAttribute("aria-label")||b.getAttribute("title")); if(t) out.buttons.push({text:t,type:(b.getAttribute("type")||b.getAttribute("role")||b.tagName).toLowerCase(),id:red(b.id),visible:vis(b)});
    });
    out.links=Array.prototype.slice.call(document.querySelectorAll("nav a, [role=navigation] a, header a"),0,40).map(function(a){ return red(a.innerText); }).filter(Boolean);
    out.forms=Array.prototype.map.call(document.forms,function(f){ var a=f.getAttribute("action")||""; try{ a=new URL(a,location.href).pathname; }catch(e){} return {method:(f.method||"get").toLowerCase(),action:a,fields:f.elements.length}; });
    out.tech={angular:!!(window.ng||document.querySelector("[ng-version]")),react:!!document.querySelector("[data-reactroot],#root,#__next"),jquery:!!window.jQuery,sapui5:!!(window.sap&&window.sap.ui),salesforce:!!window.$A,servicenow:!!window.g_form};
    return out;
  }
  function post(o){ try{ R2cap.postMessage(JSON.stringify(o)); }catch(e){} }
  // A capture request from the app reaches the main page, which passes it down to every frame.
  window.__r2scanAll=function(id){ post({k:"scan",id:id,data:scan()}); pass({r2cap:"scan",id:id}); };
  function pass(msg){ for(var i=0;i<window.frames.length;i++){ try{ window.frames[i].postMessage(msg,"*"); }catch(e){} } }
  window.addEventListener("message",function(e){ if(e.data&&e.data.r2cap==="scan"&&window!==window.top){ post({k:"scan",id:e.data.id,data:scan()}); pass(e.data); } });
  // Steps: which buttons and links you tap, and which fields you fill in (by label only, never the value).
  document.addEventListener("click",function(e){
    var b=e.target&&e.target.closest&&e.target.closest(BUTTONS+",a,label");
    if(b) post({k:"step",ev:"tap",text:red(b.innerText||b.value||b.getAttribute("aria-label")||b.getAttribute("title")),what:(b.getAttribute("role")||b.tagName).toLowerCase(),site:location.hostname,path:location.pathname});
  },true);
  document.addEventListener("change",function(e){
    var el=e.target; if(!el||!el.matches||!el.matches(FIELDS)) return;
    post({k:"step",ev:"filled",label:labelOf(el),type:(el.getAttribute("type")||el.tagName).toLowerCase(),name:red(el.name),site:location.hostname,path:location.pathname});
  },true);
})();
