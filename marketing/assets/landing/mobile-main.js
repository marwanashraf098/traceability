
(() => {
const $=(s,r=document)=>r.querySelector(s), $$=(s,r=document)=>[...r.querySelectorAll(s)];
/* nav */
const nav=$('#nav'), menu=$('#menu'), mbtn=$('#mbtn');
const closeMenu=()=>{ menu.classList.remove('open'); nav.classList.remove('open'); mbtn.setAttribute('aria-expanded','false'); };
mbtn.addEventListener('click',()=>{ const o=!menu.classList.contains('open'); menu.classList.toggle('open',o); nav.classList.toggle('open',o); mbtn.setAttribute('aria-expanded',String(o)); });
$$('a',menu).forEach(a=>a.addEventListener('click',closeMenu));
document.addEventListener('click',e=>{ if(!menu.contains(e.target)&&!mbtn.contains(e.target)) closeMenu(); });
const secs=$$('[data-theme]');
const onScroll=()=>{ nav.classList.toggle('sc',scrollY>8); const y=40; let dark=false; for(const s of secs){ const r=s.getBoundingClientRect(); if(r.top<=y&&r.bottom>y){ dark=s.dataset.theme==='dark'; break; } } nav.classList.toggle('on-dark',dark); if(scrollY>lastY+30) closeMenu(); lastY=scrollY; };
let lastY=0; addEventListener('scroll',onScroll,{passive:true}); onScroll();
/* reveal */
const io=new IntersectionObserver(es=>es.forEach(e=>{ if(e.isIntersecting){ e.target.classList.add('in'); io.unobserve(e.target); } }),{threshold:.12});
$$('.rv').forEach(el=>io.observe(el));
/* hero demo */
const apps=$$('#stage .app'), hd=$$('#hdots i'); let hi=0, ht;
const hgo=n=>{ hi=(n+apps.length)%apps.length; apps.forEach((a,i)=>a.classList.toggle('on',i===hi)); hd.forEach((d,i)=>d.classList.toggle('on',i===hi)); };
const hstart=()=>{ clearInterval(ht); ht=setInterval(()=>hgo(hi+1),4200); };
let sx=0; const st=$('#stage'); st.addEventListener('pointerdown',e=>sx=e.clientX); st.addEventListener('pointerup',e=>{ const dx=e.clientX-sx; if(Math.abs(dx)>30){ hgo(hi+(dx<0?1:-1)); hstart(); } });
hd.forEach((d,i)=>d.addEventListener('click',()=>{ hgo(i); hstart(); })); hstart();
/* trust lane: duplicate for seamless roll */
const lane=$('#lane'); lane.innerHTML+=lane.innerHTML;
/* more list -> screen */
const M={
 'Batch label printing':'<div class="top"><b>Labels</b><span>Order #4822</span></div><div class="card"><b>5 pieces · 5 labels</b><small>Zebra ZD421 · Desk 1</small><div class="row"><span>Printed</span><b>2 of 5</b></div><div class="bar"><i style="--w:40%"></i></div></div><span class="st"><i></i>Printing</span>',
 'Courier sync':'<div class="top"><b>Couriers</b><span>Live</span></div><div class="card"><div class="row" style="border:0"><span>Bosta</span><b>21 pieces</b></div><div class="row"><span>Mylerz</span><b>9 pieces</b></div><div class="row"><span>Own riders</span><b>7 pieces</b></div></div><span class="st"><i></i>37 with couriers</span>',
 'Shopify availability':'<div class="top"><b>Linen shirt · M</b><span>Shopify</span></div><div class="card"><small>Available to sell</small><div class="big">4</div><div class="row"><span>On shelf</span><b>5</b></div><div class="row"><span>On hold</span><b>−1</b></div></div><span class="st g"><i></i>Synced</span>',
 'Hold rules':'<div class="top"><b>Dispatch</b><span>Sara</span></div><div class="card"><b>TRC-0417 · Denim jacket · S</b><small>Returned Thu · not cleaned</small><div class="row"><span>Rule</span><b>Clean before resale</b></div></div><span class="st r"><i></i>Blocked at dispatch</span>',
 'Silent-piece alerts':'<div class="top"><b>Alerts</b><span>1 new</span></div><div class="card"><b>TRC-0391 · Silk scarf</b><small>Last seen: Bosta hub, Nasr City</small><div class="row"><span>Silent for</span><b>49 hours</b></div></div><span class="st r"><i></i>Ask the courier</span>',
 'Exports':'<div class="top"><b>Exports</b><span>Week 38</span></div><div class="card"><b>movements.csv</b><small>1,284 rows · per courier</small><div class="row"><span>Counts</span><b>counts.csv</b></div></div><span class="st g"><i></i>Ready</span>',
 'Team roles':'<div class="top"><b>Team</b><span>4</span></div><div class="card"><div class="row" style="border:0"><span>Omar · Owner</span><b>All</b></div><div class="row"><span>Sara · Ops</span><b>Scan, release</b></div><div class="row"><span>2 riders</span><b>Scan</b></div></div><span class="st"><i></i>Active</span>'
};
const MT=[['Batch label printing','Every piece in an order, one click, any label printer.'],['Courier sync','Bosta, Mylerz, Aramex and your own riders in one timeline.'],['Shopify availability','Holds and returns excluded from what customers can buy.'],['Hold rules','Returned pieces cannot ship until scanned back in clean.'],['Silent-piece alerts','48 hours without a scan and you hear about it first.'],['Exports','Movements and counts as CSV, per week or per courier.'],['Team roles','Who can scan, who can release a hold, who can print.']];
const msw=$('#mswipe'), mdots=$('#mdots');
msw.innerHTML=MT.map(([t,l],i)=>`<div class="mc"><div class="shot"><div class="app">${M[t]}</div></div><div class="t"><span class="n"><i></i>0${i+1} / 07</span><b>${t}</b><span>${l}</span></div></div>`).join('');
mdots.innerHTML=MT.map((_,i)=>'<i'+(i?'':' class="on"')+'></i>').join('');
const mdi=$$('i',mdots);
msw.addEventListener('scroll',()=>{ const i=Math.round(msw.scrollLeft/(msw.firstElementChild.offsetWidth+14)); mdi.forEach((d,k)=>d.classList.toggle('on',k===i)); },{passive:true});
mdi.forEach((d,i)=>d.addEventListener('click',()=>msw.scrollTo({left:i*(msw.firstElementChild.offsetWidth+14),behavior:'smooth'})));
/* use cases dots */
const sw=$('#swipe'), ud=$$('#udots i');
sw.addEventListener('scroll',()=>{ const i=Math.round(sw.scrollLeft/(sw.firstElementChild.offsetWidth+14)); ud.forEach((d,k)=>d.classList.toggle('on',k===i)); },{passive:true});
/* testimonials */
const Q=[
 ['BLNCO','assets/brand-blnco.png','Omar Aly · Founder, BLNCO','Setup took an afternoon. We labelled the whole stockroom and haven\'t lost a piece since.'],
 ['Jumi','assets/brand-jumi.png','Mohamed Afyouni · Founder, Jumi','The scanner blocks anything on hold, so returns don\'t get reshipped by mistake anymore.'],
 ['The Snouts','assets/brand-snouts.png','Mohamed Ghanem · Founder, The Snouts','Bosta claimed a parcel never reached the hub. I sent them the scan. Refunded in two days.'],
 ['BLNCO','assets/brand-blnco.png','Omar Aly · BLNCO','Shopify stock finally matches what\'s actually on the shelf. Oversells stopped.'],
 ['Jumi','assets/brand-jumi.png','Mohamed Afyouni · Jumi','My team scans from their phones, I see every move from the laptop. Simple.'],
 ['The Snouts','assets/brand-snouts.png','Mohamed Ghanem · The Snouts','Wish we had this a year ago. Would have saved us thousands in write-offs.']
];
const card=r=>`<div class="tq"><div class="lg"><img src="${r[1]}" alt="${r[0]}"></div><div><div class="top"><b>${r[0]}</b><span class="stars">★★★★★</span></div><q>${r[3]}</q><div class="who">${r[2]}</div></div></div>`;
$('#tlane').innerHTML=Q.map(card).join('')+Q.map(card).join('');
/* pricing */
const PLANS=[
 {id:'free',name:'Free',limit:200,lim:'Up to 200 pieces a month · 1 user',feat:['A barcode for every piece, from the Shopify order','Scan with your phone: received, shipped, returned','The full story of each piece','Shopify availability, live']},
 {id:'pro',name:'Pro',limit:Infinity,lim:'Unlimited pieces · up to 5 users',feat:['All of Free','Every courier in one timeline: Bosta, Mylerz, Aramex, your riders','Holds, cleaning states, alerts when a piece goes quiet','Batch labels, exports, team roles']}
];
const PRICE={m:{v:900,unit:' / month',was:''},y:{v:750,unit:' / month',was:'EGP 9,000 billed yearly · 2 months free'}};
let bill='m', rec='pro';
const fmt=n=>'EGP '+Math.round(n).toLocaleString('en-EG');
const renderPlans=()=>{ $('#plans').innerHTML=PLANS.map(p=>{ const pro=p.id==='pro'; const pr=pro?`<div class="price"><s>${fmt(PRICE[bill].v)}</s>EGP 0<small>${PRICE[bill].unit}</small></div>${PRICE[bill].was?'<span class="was">'+PRICE[bill].was+'</span>':''}<span class="free"><i></i>Free during launch</span>`:`<div class="price">EGP 0<small> / month</small></div>`;
  return `<div class="plan ${p.id===rec?'rec':''}"><span class="tag"><i></i>For your numbers</span><h3>${p.name}</h3>${pr}<div class="lim">${p.lim}</div><ul>${p.feat.map(f=>'<li>'+f+'</li>').join('')}</ul><a class="pill ${pro?'blue':'ghost'}" href="https://app.tracedtech.com/signup">${pro?'Start Pro free':'Start free'}</a></div>`; }).join(''); };
$$('#bill button').forEach(b=>b.addEventListener('click',()=>{ bill=b.dataset.b; $$('#bill button').forEach(x=>x.classList.toggle('on',x===b)); renderPlans(); }));
const get=k=>+$(`[data-i="${k}"]`).value, set=(k,v)=>{ const el=$(`[data-o="${k}"]`); if(el) el.innerHTML=v; };
const run=()=>{ const o=get('orders'),v=get('value'),r=get('ret')/100,d=get('dmg')/100,l=get('lost')/100,rc=get('rec')/100;
  set('orders',o.toLocaleString()); set('value',fmt(v)); set('ret',Math.round(r*100)+'%'); set('dmg',Math.round(d*100)+'%'); set('lost',get('lost')+'%'); set('rec',Math.round(rc*100)+'%');
  const v1=o*v*l*rc, v2=o*r*d*v; const total=v1+v2; set('v1',fmt(v1)); set('v2',fmt(v2)); $('#total').textContent=fmt(total);
  const price=PRICE[bill].v; const days=total>0?Math.max(1,Math.ceil(price/(total/30))):0; set('pay',days?days+(days===1?' day':' days'):'—');
  rec=o>200?'pro':'free'; renderPlans();
  $$('.calc input').forEach(i=>i.style.setProperty('--p',((i.value-i.min)/(i.max-i.min)*100)+'%')); };
$$('.calc input').forEach(i=>i.addEventListener('input',run)); run();
/* why */
const B=[
 {w:'proven.',sub:'A courier says it never arrived. You have the scan, the name and the time.',html:'<div class="h"><i></i><span class="t">Bosta</span><span class="tag">Delivered</span></div><div class="row"><span>Signed by</span><b>Mariam A.</b></div><div class="row"><span>Time</span><b>Tue 14:12</b></div><div class="row"><span>Claim</span><b>Refunded</b></div>'},
 {w:'counted.',sub:'Two pieces move on every exchange. Both are counted, holds are excluded.',html:'<div class="h"><i></i><span class="t">Linen shirt · M</span><span class="tag">Synced</span></div><div class="row"><span>On shelf</span><b>4</b></div><div class="row"><span>On hold</span><b>1</b></div><div class="row"><span>Shopify shows</span><b>4</b></div>'},
 {w:'found.',sub:'A piece goes quiet for 48 hours and you hear about it before the customer does.',html:'<div class="h"><i class="r"></i><span class="t">TRC-0391</span><span class="tag">49h silent</span></div><div class="row"><span>Last seen</span><b>Nasr City hub</b></div><div class="row"><span>Courier</span><b>Bosta</b></div><div class="row"><span>Action</span><b>Ask sent</b></div>'}
];
const word=$('#word'), wsub=$('#wsub'), wcard=$('#wcard'), wd=$('#wdots'); let wi=-1, wt;
B.forEach((b,i)=>{ const s=document.createElement('span'); s.textContent=b.w; word.appendChild(s); const d=document.createElement('i'); d.addEventListener('click',()=>{ wgo(i); wstart(); }); wd.appendChild(d); });
const wgo=i=>{ wi=(i+B.length)%B.length; $$('span',word).forEach((s,k)=>s.classList.toggle('on',k===wi)); [...wd.children].forEach((d,k)=>d.classList.toggle('on',k===wi)); wsub.textContent=B[wi].sub; const c=document.createElement('div'); c.className='wcard'; c.innerHTML=B[wi].html; wcard.innerHTML=''; wcard.appendChild(c); requestAnimationFrame(()=>c.classList.add('on')); };
const wstart=()=>{ clearInterval(wt); wt=setInterval(()=>wgo(wi+1),5200); };
$('#why').addEventListener('click',e=>{ if(e.target.closest('.sig,.k')) return; wgo(wi+1); wstart(); }); wgo(0); wstart();
/* faq */
const F=[
 ['What do I need to start?','No new setup. If you already use Shopify + Bosta, keep your current workflow. Use your Bosta flyer printer for shipping flyers and barcodes, plus any barcode scanner for warehouse scanning.'],
 ['Do I need a special scanner?','No. Any standard barcode scanner works with Traced.'],
 ['Do I need to replace Shopify or Bosta?','No. Traced integrates with both and connects your store, warehouse and delivery flow in one place.'],
 ['What if the wrong piece is scanned?','Traced flags it immediately. The order can\u2019t move forward until the correct piece is scanned.'],
 ['What happens after dispatch?','Traced keeps tracking through Bosta, including Delivered, Failed Delivery, Exchange, Return and Refund.'],
 ['Is Traced only for fashion brands?','No. Traced is built for ecommerce brands. For now, if you use Shopify + Bosta, you\u2019re good to go.'],
 ['Is the paid plan really free right now?','Yes. Every brand that joins during launch gets the paid plan at no cost. When pricing starts, you\u2019ll be told a month ahead and can stay on Free.','new'],
 ['Who can see my stock and orders?','Only your team. Each person gets their own login and role; every scan is signed with a name. We never share or sell your data.','new']
];
$('#acc').innerHTML=F.map(([q,a,n],i)=>`<details${n?' data-new':''}${i===0?' open':''}><summary><span class="n">${String(i+1).padStart(2,'0')}</span><span>${q}</span><span class="c"><svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg></span></summary><p>${a}</p></details>`).join('');
})();
