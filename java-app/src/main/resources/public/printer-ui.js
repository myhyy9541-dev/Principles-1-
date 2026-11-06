// Optional printer workflow module loaded by renderer.js
const pendingPrintListRequests = new Map();
const pendingPrintOffers = new Map();

function renderPrinterSettings(view) {
  const enabled = Boolean(view?.shareEnabled), shared = new Set(view?.sharedPrinterIds || []);
  $('sharePrintersCheck').checked = enabled;
  const list = $('printerList'); list.replaceChildren();
  const printers = view?.printers || [];
  if (!printers.length) list.textContent = 'لم يعثر Windows على طابعات مثبتة لهذا المستخدم.';
  for (const printer of printers) {
    const label = document.createElement('label'); label.className = 'printer-option';
    const checkbox = document.createElement('input'); checkbox.type = 'checkbox'; checkbox.dataset.sharedPrinter = '1'; checkbox.value = printer.id;
    checkbox.checked = shared.has(printer.id); checkbox.disabled = !enabled;
    const name = document.createElement('span'); name.textContent = printer.name + (printer.default ? ' · الافتراضية' : '');
    const formats = document.createElement('small'); formats.textContent = (printer.formats || []).join('، ') || 'يختلف الدعم حسب تعريف Windows';
    label.append(checkbox,name,formats); list.append(label);
  }
  const grants = $('printerPermissionsList'); grants.replaceChildren();
  if (!(view?.permissions || []).length) grants.textContent = 'لا توجد أذونات محفوظة.';
  for (const grant of view.permissions || []) {
    const row = document.createElement('div'); row.className = 'printer-permission';
    const text = document.createElement('span'); text.textContent = `${grant.senderName || grant.senderClientId} ← ${grant.printerName || grant.printerId}`;
    const revoke = document.createElement('button'); revoke.type='button'; revoke.textContent='إلغاء الإذن';
    revoke.onclick = async () => { try { printerView = await window.wasel.setPrinterPermission({senderClientId:grant.senderClientId,printerId:grant.printerId,revoke:true}); renderPrinterSettings(printerView); toast('أُلغي إذن الطباعة.'); } catch(error) { toast(error.message); } };
    row.append(text,revoke); grants.append(row);
  }
}

$('sharePrintersCheck').addEventListener('change', () => {
  document.querySelectorAll('[data-shared-printer]').forEach(box => { box.disabled = !$('sharePrintersCheck').checked; });
});
$('printerList').addEventListener('change', () => {
  // Values are persisted with the regular settings form.
});

function choosePrinterOption(title, description, options, confirmText='اختيار') {
  if (!options?.length) return Promise.resolve(null);
  return new Promise(resolve => {
    const dialog=document.createElement('dialog'); dialog.className='recipient-dialog';
    const form=document.createElement('form'); form.method='dialog';
    const heading=document.createElement('h2'); heading.textContent=title;
    const hint=document.createElement('p'); hint.textContent=description;
    const list=document.createElement('div'); list.className='recipient-list';
    options.forEach((option,index)=>{
      const label=document.createElement('label'); label.className='recipient-item';
      const input=document.createElement('input'); input.type='radio'; input.name='printer-choice'; input.value=option.id; input.checked=index===0;
      const name=document.createElement('span'); name.textContent=option.name;
      label.append(input,name); list.append(label);
    });
    const actions=document.createElement('div'); actions.className='recipient-actions';
    const cancel=document.createElement('button'); cancel.type='button'; cancel.className='call-control'; cancel.textContent='إلغاء';
    const select=document.createElement('button'); select.type='button'; select.className='icon-btn primary'; select.textContent=confirmText;
    const finish=value=>{if(dialog.open)dialog.close();dialog.remove();resolve(value);};
    cancel.onclick=()=>finish(null); select.onclick=()=>finish(options.find(item=>item.id===form.querySelector('input:checked')?.value)||null);
    dialog.addEventListener('cancel',event=>{event.preventDefault();finish(null);},{once:true});
    actions.append(cancel,select); form.append(heading,hint,list,actions); dialog.append(form); document.body.append(dialog); dialog.showModal();
  });
}

function activePrintTargets() {
  if (connectionMode==='direct') return [...discoveredPeers.values()].filter(peer=>peer.clientId!==clientId && peerSockets.get(peer.clientId)?.socket.connected).map(peer=>({id:peer.clientId,name:peer.name||'مستخدم'}));
  return currentMembers.filter(user=>user.clientId!==clientId).map(user=>({id:user.clientId,name:user.name||'مستخدم'}));
}
function printTransport(targetId) { return connectionMode==='direct' ? peerSockets.get(targetId)?.socket : socket; }
function waitForPrinterList(requestId, timeoutMs=12000) {
  return new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>{pendingPrintListRequests.delete(requestId);reject(new Error('انتهت مهلة انتظار قائمة الطابعات.'));},timeoutMs);
    pendingPrintListRequests.set(requestId,printers=>{clearTimeout(timer);pendingPrintListRequests.delete(requestId);resolve(printers);});
  });
}
async function publishPrinterList(data, source) {
  try {
    const view=await window.wasel.getPrinters();
    const ids=new Set(view.shareEnabled ? (view.sharedPrinterIds||[]) : []);
    const printers=(view.printers||[]).filter(printer=>ids.has(printer.id));
    source.emit('print-list',{requesterClientId:data.requesterClientId,requestId:data.requestId,printers});
  } catch (_) { source.emit('print-list',{requesterClientId:data.requesterClientId,requestId:data.requestId,printers:[]}); }
}
async function requestRemotePrint(message) {
  if (!connected && connectionMode==='host') throw new Error('اتصل بغرفة المضيف أولاً.');
  if (Number(message.size||0)>32*1024*1024) throw new Error('الطباعة عن بُعد تقبل ملفات حتى 32 ميغابايت حالياً.');
  const targets=activePrintTargets();
  if (!targets.length) throw new Error('لا يوجد مستخدم متصل يمكنه مشاركة طابعة الآن.');
  const target=await choosePrinterOption('اختيار جهاز الطباعة','اختر جهازاً يشارك طابعة عبر واصل.',targets,'عرض الطابعات');
  if (!target) return;
  const transport=printTransport(target.id); if (!transport?.connected) throw new Error('انقطع اتصال الجهاز المحدد.');
  const requestId=crypto.randomUUID(), listPromise=waitForPrinterList(requestId);
  transport.emit('print-list-request',{targetClientId:target.id,requestId});
  const printers=await listPromise;
  if (!printers?.length) throw new Error(`${target.name} لم يفعّل مشاركة أي طابعة في إعدادات واصل.`);
  const printer=await choosePrinterOption('اختيار الطابعة',`الطابعات التي يشاركها ${target.name}:`,printers,'اختيار للطباعة');
  if (!printer) return;
  const fileHost=connectionMode==='direct'?'127.0.0.1':(currentHost?.address||'');
  const filePort=connectionMode==='direct'?43822:(currentHost?.filePort||43822);
  if (!fileHost) throw new Error('تعذر تحديد مضيف الملف.');
  transport.emit('print-offer',{targetClientId:target.id,fileId:message.id,printerId:printer.id,fileHost,filePort});
  toast(`أُرسل طلب الطباعة إلى ${target.name}؛ ستظهر الموافقة على جهازه عند أول استخدام.`);
}

async function receivePrintOffer(offer, source) {
  let result={ok:false,status:''};
  try {
    const view=await window.wasel.getPrinters();
    if (!view.shareEnabled || !(view.sharedPrinterIds||[]).includes(offer.printerId)) throw new Error('الطابعة لا تسمح حالياً بالطباعة عبر واصل.');
    const printer=(view.printers||[]).find(item=>item.id===offer.printerId);
    if (!printer) throw new Error('الطابعة لم تعد مثبتة أو تغيّر اسمها.');
    const grant=(view.permissions||[]).some(item=>item.senderClientId===offer.senderClientId && item.printerId===offer.printerId);
    if (!grant) {
      const ok=await showDialog('موافقة الطباعة الأولى',`${offer.senderName} يطلب طباعة الملف «${offer.fileName}» (${formatSize(offer.size)}) على الطابعة «${printer.name}». بالموافقة ستُطبع هذه المهمة، وستُطبع مهامه اللاحقة على هذه الطابعة تلقائياً حتى تلغي الإذن من الإعدادات.`,{yesNo:true});
      if (!ok) throw new Error('رُفض طلب الطباعة.');
      printerView=await window.wasel.setPrinterPermission({senderClientId:offer.senderClientId,senderName:offer.senderName,printerId:printer.id,printerName:printer.name});
      renderPrinterSettings(printerView);
    }
    const host=offer.fileHost||'127.0.0.1', port=Number(offer.filePort)||43822;
    if (!/^(?:\d{1,3}\.){3}\d{1,3}$/.test(host)) throw new Error('عنوان مصدر الملف غير صالح.');
    const controller=new AbortController(),timeout=setTimeout(()=>controller.abort(),30000);
    let response;try{response=await fetch(`http://${host}:${port}/api/files/${encodeURIComponent(offer.fileId)}`,{cache:'no-store',signal:controller.signal});}finally{clearTimeout(timeout);}
    if (!response.ok) throw new Error(`تعذر تنزيل ملف الطباعة من الشبكة (HTTP ${response.status}).`);
    const blob=await response.blob();
    if (!blob.size || blob.size>32*1024*1024) throw new Error('حجم الملف لا يناسب الطباعة.');
    const printed=await window.wasel.printLocal({blob,senderClientId:offer.senderClientId,printerId:printer.id,filename:offer.fileName});
    result={ok:true,status:printed.status||'أُرسلت المهمة إلى Windows؛ تحقق من قائمة الطباعة.'};
  } catch(error) { result={ok:false,status:error.message||'تعذرت الطباعة.'}; }
  source.emit('print-result',{targetClientId:offer.senderClientId,requestId:offer.requestId,ok:result.ok,status:result.status});
  if (!result.ok) toast(result.status); else toast(`تم إرسال المهمة إلى قائمة Windows. ${result.status}`);
}
