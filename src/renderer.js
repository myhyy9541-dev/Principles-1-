const $ = id => document.getElementById(id);
const esc = s => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let myName = localStorage.getItem('wasel-name') || '';
if (!myName) { myName = (prompt('اكتب اسمك الظاهر للفريق:') || 'مستخدم').trim().slice(0,40) || 'مستخدم'; localStorage.setItem('wasel-name', myName); }
let connected = false, hostMode = false, currentHost = null, pendingFile = null, localStream = null, callMode = null, currentMembers = [];
const peers = new Map();
function toast(text) { const el=$('toast'); el.textContent=text; el.classList.remove('hidden'); setTimeout(()=>el.classList.add('hidden'),3500); }
function time(ts) { return new Date(ts||Date.now()).toLocaleTimeString('ar',{hour:'2-digit',minute:'2-digit'}); }
function addMessage(m) {
  const box=$('messages'); const welcome=box.querySelector('.welcome'); if(welcome) welcome.remove();
  if(m.type==='system'){const x=document.createElement('div');x.className='system-message';x.textContent=m.text;box.append(x);box.scrollTop=box.scrollHeight;return;}
  const mine=m.senderId===window._socketId;
  const row=document.createElement('div');row.className='message'+(mine?' mine':'');
  const initial=esc((m.user||'?').slice(0,1));
  let content=esc(m.text||'');
  if(m.type==='file'){
    const data=m.data||''; content=`<a class="file-card" download="${esc(m.name)}" href="${data}"><span class="file-icon">▤</span><span>${esc(m.name)}<small>ملف مشترك · ${esc(m.user)}</small></span><span>تنزيل</span></a>`;
  }
  row.innerHTML=`<div class="msg-avatar">${initial}</div><div class="bubble-wrap"><div class="msg-meta"><b>${esc(m.user||'النظام')}</b><span class="msg-time">${time(m.time)}</span></div><div class="bubble">${content}</div></div>`;
  box.append(row);box.scrollTop=box.scrollHeight;
}
function handleEvent(event,data){
 switch(event){
  case 'connect': connected=true; window._socketId=data?.id; $('connectionText').textContent='متصل بالشبكة المحلية';$('roomTitle').textContent='غرفة المنشأة';break;
  case 'connect_error': connected=false; $('connectionText').textContent='تعذر الاتصال بالمضيف'; break;
  case 'history': data.forEach(addMessage);break;
  case 'members': currentMembers=data; $('memberCount').textContent=data.length; $('members').innerHTML=data.map(u=>`<div class="member"><span class="avatar">${esc(u.name.slice(0,1))}</span>${esc(u.name)}${u.id===window._socketId?'<span class="green-dot"></span>':''}</div>`).join(''); if(callMode) data.filter(u=>u.id!==window._socketId).forEach(u=>makePeer(u.id,true));break;
  case 'system-message': addMessage({type:'system',text:data.text});break;
  case 'chat-message': addMessage(data);break;
  case 'file-message': addMessage({type:'file',...data});break;
  case 'notice': toast(data);break;
  case 'signal': receiveSignal(data).catch(e=>toast('تعذّر إعداد الاتصال: '+e.message));break;
 }
}
function connect(address,port){if(connected)return;currentHost={address,port};$('connectionText').textContent=`الاتصال بـ ${address}…`;window.wasel.connectRoom(`http://${address}:${port}`,myName,handleEvent);}
window.wasel.onRoomFound(room=>{if(!connected&&!hostMode){$('connectionText').textContent=`عُثر على غرفة في ${room.address}`;connect(room.address,room.port);}});
window.wasel.onRoomStarted(data=>{hostMode=true; $('roomBanner').classList.remove('hidden');$('hostAddress').textContent=`العنوان: ${data.addresses.join(' · ')} — المنفذ ${data.port}`;connect('127.0.0.1',data.port);});
window.wasel.onNotice(text=>toast(text));
$('createRoom').onclick=async()=>{const b=$('createRoom');b.disabled=true;b.textContent='جارٍ إنشاء الغرفة…';const r=await window.wasel.startRoom();if(!r.ok){toast('تعذّر إنشاء الغرفة');b.disabled=false;b.textContent='＋ إنشاء غرفة جديدة';}else{b.textContent='الغرفة تعمل على هذا الجهاز';toast('تم إنشاء الغرفة؛ تأكد أن الأجهزة على الشبكة نفسها.');}};
function sendText(){const input=$('messageInput'),text=input.value.trim();if(!text)return;if(!connected)return toast('انتظر اتصال الجهاز بالمضيف');window.wasel.send('chat',{text});input.value='';input.style.height='auto';}
$('sendBtn').onclick=sendText;$('messageInput').addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();sendText();}});$('messageInput').addEventListener('input',e=>{e.target.style.height='auto';e.target.style.height=Math.min(e.target.scrollHeight,110)+'px';});
$('attachBtn').onclick=()=>$('fileInput').click();
$('fileInput').onchange=async e=>{const f=e.target.files[0];if(!f)return;if(f.size>10*1024*1024){toast('حجم الملف يتجاوز 10 ميغابايت');e.target.value='';return;}pendingFile=f;$('filePreview').textContent=`جاهز للإرسال: ${f.name} (${(f.size/1024).toFixed(0)} كيلوبايت) — اضغط إرسال للمشاركة`;$('filePreview').classList.remove('hidden');$('sendBtn').onclick=sendFile;};
async function sendFile(){if(!pendingFile)return sendText();if(!connected)return toast('انتظر اتصال الجهاز بالمضيف');const f=pendingFile;const data=await new Promise((resolve,reject)=>{const r=new FileReader();r.onload=()=>resolve(r.result);r.onerror=reject;r.readAsDataURL(f);});window.wasel.send('file',{name:f.name,mime:f.type,data});pendingFile=null;$('fileInput').value='';$('filePreview').classList.add('hidden');$('sendBtn').onclick=sendText;}
let pendingMode=null;
async function startCall(mode,initiator=true){if(!connected)return toast('اتصل بغرفة محلية أولاً');try{localStream=await navigator.mediaDevices.getUserMedia({audio:true,video:mode==='video'});callMode=mode;$('callTitle').textContent=mode==='video'?'مكالمة فيديو':'مكالمة صوتية';$('callStatus').textContent='اتصال محلي مباشر';$('callPanel').classList.remove('hidden');$('videoGrid').innerHTML='';if(mode==='video')addVideo('أنت',localStream,true);if(initiator){currentMembers.filter(u=>u.id!==window._socketId).forEach(u=>makePeer(u.id,true));window.wasel.send('signal',{to:'*',description:{type:'call-start',mode}});}
} catch(e){toast('تعذر الوصول إلى الميكروفون/الكاميرا: '+e.message);}}
$('voiceBtn').onclick=()=>startCall('audio');$('videoBtn').onclick=()=>startCall('video');
function addVideo(label,stream,local=false){const v=document.createElement('video');v.autoplay=true;v.playsInline=true;v.muted=local;v.srcObject=stream;if(local)v.className='local-video';v.title=label;$('videoGrid').append(v);return v;}
function makePeer(id,offer){if(!localStream||id===window._socketId)return;let pc=peers.get(id);if(pc)return pc;pc=new RTCPeerConnection({iceServers:[]});peers.set(id,pc);localStream.getTracks().forEach(t=>pc.addTrack(t,localStream));pc.onicecandidate=e=>{if(e.candidate)window.wasel.send('signal',{to:id,candidate:e.candidate});};pc.ontrack=e=>{if(!document.querySelector(`[data-peer="${id}"]`)){const v=addVideo('مشارك',e.streams[0]);v.dataset.peer=id;}};pc.onconnectionstatechange=()=>{if(['failed','disconnected','closed'].includes(pc.connectionState))toast('انقطع الاتصال المباشر بأحد المشاركين');};if(offer)pc.createOffer().then(o=>pc.setLocalDescription(o)).then(()=>window.wasel.send('signal',{to:id,description:pc.localDescription}));return pc;}
async function receiveSignal(p){if(p.description?.type==='call-start'){pendingMode=p.description.mode;if(!localStream){const yes=confirm(`بدأ ${pendingMode==='video'?'اتصال فيديو':'اتصال صوتي'} محلي. هل تريد الانضمام؟`);if(yes)await startCall(pendingMode,false);}return;}let pc=makePeer(p.from,false);if(!pc)return;if(p.description){await pc.setRemoteDescription(p.description);if(p.description.type==='offer'){const ans=await pc.createAnswer();await pc.setLocalDescription(ans);window.wasel.send('signal',{to:p.from,description:pc.localDescription});}}if(p.candidate)try{await pc.addIceCandidate(p.candidate);}catch(_){}}
$('endCall').onclick=()=>{for(const pc of peers.values())pc.close();peers.clear();localStream?.getTracks().forEach(t=>t.stop());localStream=null;callMode=null;$('callPanel').classList.add('hidden');toast('تم إنهاء الاتصال');};
window.addEventListener('beforeunload',()=>{window.wasel.disconnect();});
window.wasel.lanInfo().then(info=>{if(!connected)$('connectionText').textContent=`بانتظار مضيف على الشبكة · ${info.hostname}`;});
