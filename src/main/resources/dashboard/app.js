'use strict';
const $=id=>document.getElementById(id), num=n=>new Intl.NumberFormat('en-US').format(n);
let token=null, live=false, auto=null, running=0, authBusy=false, eventKey=null;
$('address').textContent=location.host;
function node(tag,text,cls){const el=document.createElement(tag);el.textContent=text;if(cls)el.className=cls;return el;}
function stopAuto(){clearInterval(auto);auto=null;$('auto').setAttribute('aria-pressed','false');}
function controls(enabled){['send','burst','auto','anonymous'].forEach(id=>$(id).disabled=!enabled);}
function connection(connected,message){live=connected;$('connection').textContent=message;$('connection').className=connected?'live':'stale';$('workspace').classList.toggle('outdated',!connected);controls(connected);if(!connected)stopAuto();}
function logout(message='Signed out. Sign in to reconnect.'){
  token=null;stopAuto();connection(false,message);$('auth-panel').hidden=false;$('logout').hidden=true;
  $('workspace').classList.add('locked');$('password').value='';
}
async function authenticate(register=false){
  if(authBusy || !$('auth-form').reportValidity())return;
  authBusy=true;$('login').disabled=true;$('register').disabled=true;
  $('auth-feedback').className='';$('auth-feedback').textContent=register?'Creating your account…':'Verifying credentials…';
  try{
    const response=await fetch(register?'/auth/register':'/auth/login',{method:'POST',credentials:'omit',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:$('username').value,password:$('password').value}),signal:AbortSignal.timeout(7000)});
    const data=await response.json();if(!response.ok)throw new Error(data.error||`HTTP ${response.status}`);
    if(register){$('auth-feedback').className='success';$('auth-feedback').textContent='Account created. Sign in with the same credentials.';}
    else{token=data.accessToken;$('password').value='';$('auth-panel').hidden=true;$('logout').hidden=false;$('workspace').classList.remove('locked');$('auth-feedback').textContent='';$('request-feedback').textContent=`Signed in as ${data.username}. Choose a request to explore.`;await refresh();}
  }catch(error){$('auth-feedback').className='error';$('auth-feedback').textContent=error.message;}
  finally{authBusy=false;$('login').disabled=false;$('register').disabled=false;}
}
$('auth-form').addEventListener('submit',event=>{event.preventDefault();authenticate();});
$('register').addEventListener('click',()=>authenticate(true));$('logout').addEventListener('click',()=>logout());
function draw(data){
  const t=data.telemetry;
  $('requests').textContent=num(t.requests);$('completed').textContent=`${num(t.completed)} completed · ${num(t.unauthorized)} auth denied`;
  $('active').textContent=num(t.active);$('hits').textContent=num(t.cacheHits);$('cache-count').textContent=`${data.cacheEntries} entries · ${num(t.cacheMisses)} misses`;
  $('limited').textContent=num(t.rateLimited);$('retries').textContent=num(t.retries);$('latency').textContent=t.completed?`${Math.round(t.averageResponseMs)} ms`:'—';
  $('updated').textContent=`Sampled ${new Date(t.observedAt).toLocaleTimeString()}`;
  $('rate-policy').textContent=`${data.policy.rateCapacity} tokens · +${data.policy.rateRefill}/sec per user/service`;
  $('strategy').textContent=`${data.routes[0]?.strategy==='LEAST_CONNECTIONS'?'Least connections':'Round robin'} · health + circuits`;
  const cards=document.createDocumentFragment();
  data.routes.forEach(route=>{
    const card=node('article','','panel'),head=node('div','','route-head'),title=node('div','');
    title.append(node('strong',`${route.id.charAt(0).toUpperCase()+route.id.slice(1)} service`,'route-title'),node('code',`${route.prefix}/* → /*`,'route-prefix'));
    head.append(title,node('span',route.cacheEnabled?'CACHE ELIGIBLE':'PASS THROUGH','badge'));card.append(head);
    const replicas=node('div','','replicas');
    route.backends.forEach(backend=>{
      const row=node('div','','replica'),states=node('span','','states');
      states.append(node('span',backend.healthy?'● Ready':'● Down',backend.healthy?'healthy':'down'),node('span',backend.circuit,backend.circuit.toLowerCase().replace('_','-')));
      row.append(node('strong',new URL(backend.origin).host),states,node('small',`${backend.active} active · ${backend.attempts} attempts · ${backend.consecutiveFailures} consecutive failures${backend.circuit==='OPEN'?` · probe eligible in ${(backend.retryAfterMs/1000).toFixed(1)}s`:''}`,'detail'));
      row.title=`${backend.origin} /health: ${backend.lastHealthCheck?new Date(backend.lastHealthCheck).toLocaleTimeString():'not yet checked'}`;replicas.append(row);
    });card.append(replicas);cards.append(card);
  });$('routes').replaceChildren(cards);
  const key=t.events.length?`${t.events[0].sequence}/${t.events[0].at}`:'empty';
  if(key!==eventKey){eventKey=key;const rows=document.createDocumentFragment();
    t.events.forEach(event=>{const row=node('tr','');row.title=`Request ID: ${event.requestId}`;
      [new Date(event.at).toLocaleTimeString(),event.service,event.method,String(event.status),event.outcome,String(event.attempts),event.backend?new URL(event.backend).host:'—',`${Math.round(event.durationMs)} ms`].forEach((value,i)=>row.append(node('td',value,i===3?(event.status<400?'healthy':'down'):'')));rows.append(row);});
    if(!t.events.length){const row=node('tr',''),cell=node('td','Send an actual request to see the gateway’s decision.','empty');cell.colSpan=8;row.append(cell);rows.append(row);}
    $('events').replaceChildren(rows);$('event-count').textContent=`${t.events.length} / 50 EVENTS`;
  }
}
let refreshing=false;
async function refresh(){
  if(!token||document.hidden||refreshing)return;refreshing=true;const session=token;
  try{const response=await fetch('/_gateway/status',{credentials:'omit',headers:{Authorization:`Bearer ${session}`},cache:'no-store',signal:AbortSignal.timeout(2500)});
    if(session!==token)return;
    if(response.status===401){logout('Session expired. Please sign in.');return;}
    if(!response.ok)throw new Error(`HTTP ${response.status}`);
    const data=await response.json();if(session!==token)return;draw(data);connection(true,`Live · ${data.username} · 500 ms`);
  }catch(error){if(session===token)connection(false,'Disconnected · last snapshot is stale');}finally{refreshing=false;}
}
async function send(anonymous=false){
  if(!live||running>=24)return;running++;const session=token;
  const scenario=$('scenario').value;
  const path=scenario==='catalog'?'/api/catalog/items':scenario==='orders'||scenario==='mutation'?'/api/orders/items':scenario==='failure'?'/api/orders/fail':'/api/orders/slow';
  const method=scenario==='mutation'?'POST':'GET',headers=anonymous?{}:{Authorization:`Bearer ${session}`};
  if(method==='POST')headers['Content-Type']='application/json';
  $('request-feedback').className='';$('request-feedback').textContent=`${running} request(s) in flight from this tab…`;
  // Responses already carry Cache-Control: no-store. A fetch cache override here would
  // send bypass headers and unintentionally disable the gateway's internal cache.
  try{const response=await fetch(path,{method,credentials:'omit',headers,body:method==='POST'?JSON.stringify({item:'demo',quantity:1}):undefined,signal:AbortSignal.timeout(5000)});
    const body=await response.text();if(session!==token)return;
    $('request-feedback').className=response.ok?'success':'error';$('request-feedback').textContent=`${method} ${path} → HTTP ${response.status}`;
    $('response-meta').textContent=`${response.headers.get('X-Gateway-Outcome')||'AUTH_DENIED'} · attempts: ${response.headers.get('X-Gateway-Attempts')||0} · remaining: ${response.headers.get('X-RateLimit-Remaining')||'—'}${response.headers.get('Retry-After')?` · retry after ${response.headers.get('Retry-After')}s`:''}`;
    try{$('response').textContent=JSON.stringify(JSON.parse(body),null,2);}catch{$('response').textContent=body.slice(0,4096);}
  }catch(error){if(session===token){$('request-feedback').className='error';$('request-feedback').textContent=error.message;}}finally{running--;}
}
$('send').addEventListener('click',()=>send());$('burst').addEventListener('click',()=>{for(let i=0;i<20;i++)send();});$('anonymous').addEventListener('click',()=>send(true));
$('auto').addEventListener('click',()=>{if(auto)stopAuto();else{send();auto=setInterval(send,250);$('auto').setAttribute('aria-pressed','true');}});
document.addEventListener('visibilitychange',()=>{if(document.hidden){stopAuto();if(token)connection(false,'Paused · tab is hidden');}else refresh();});
setInterval(refresh,500);
