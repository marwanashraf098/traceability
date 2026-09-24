window.__img=p=>{const e=document.querySelector('#imgPre img[data-k="'+p+'"]');return e?e.getAttribute('src'):p;};
(() => {
  const p = document.getElementById('threadPath'); if (!p) return;
  const len = p.getTotalLength(); p.style.setProperty('--len', len);
})();
(() => {
  const SECTIONS = [
    ['features','Features'],['use-cases','Use cases'],['brands','Testimonials'],['pricing','Pricing'],
    ['integrations','Integrations'],['why','Why Traced'],['faq','FAQ']
  ];
  const nav = document.getElementById('nav'), links = document.getElementById('links'),
        panel = null;

  // one list, two renderings
  const make = (host, cls) => SECTIONS.forEach(([id, label]) => {
    const a = document.createElement('a'); a.href = '#' + id; a.textContent = label; a.dataset.target = id; if (cls) a.className = cls; host.appendChild(a);
  });
  make(links, 'lnk');

  const navH = () => nav.getBoundingClientRect().height;
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const go = id => {
    const el = document.getElementById(id); if (!el) return;
    let top = el.getBoundingClientRect().top + scrollY - (id === 'hero' ? 0 : navH() - 1);
    if (id === 'use-cases') top = el.getBoundingClientRect().top + scrollY + innerHeight * 1.02;
    if (id === 'features') top = el.getBoundingClientRect().top + scrollY;
    scrollTo({ top, behavior: reduce ? 'auto' : 'smooth' });
    history.replaceState(null, '', '#' + id);
  };
  document.addEventListener('click', e => {
    const a = e.target.closest('a[href^="#"]'); if (!a) return;
    const id = a.getAttribute('href').slice(1);
    if (document.getElementById(id)) { e.preventDefault(); go(id); }
  });

  // scroll state: frosted, theme inversion, active link
  const sections = [...document.querySelectorAll('main section')];
  let scrolled = false, light = false, active = '', raf = 0, lastTheme = 'dark';
  const update = () => {
    raf = 0;
    const s = scrollY > 24; if (s !== scrolled) { scrolled = s; nav.classList.toggle('is-scrolled', s); }

    // theme: the section under the bar's vertical middle, with a small hysteresis so it never flickers at the edge
    const probe = navH() / 2;
    let theme = lastTheme;
    for (const sec of sections) {
      const r = sec.getBoundingClientRect();
      if (r.top <= probe && r.bottom > probe) { theme = sec.dataset.theme; break; }
    }
    if (theme !== lastTheme) { lastTheme = theme; nav.classList.toggle('is-light', theme === 'light'); }

    // active: the section whose top has passed the bar
    const line = navH() + 8; let cur = '';
    for (const sec of sections) { const t = sec.getBoundingClientRect().top; if (sec.id === 'features' ? t <= line - innerHeight * .35 : t <= line) cur = sec.id; }
    if (cur !== active) {
      active = cur;
      document.querySelectorAll('#links a').forEach(a => a.classList.toggle('is-active', a.dataset.target === active));
    }
  };
  const tick = () => { if (!raf) raf = requestAnimationFrame(update); };
  addEventListener('scroll', tick, { passive: true }); addEventListener('resize', tick); update();

  if (location.hash) setTimeout(() => go(location.hash.slice(1)), 60);

  const lg = document.querySelector('.logos'); if (lg) { const setLW = () => lg.style.setProperty('--lw', lg.clientWidth + 'px'); setLW(); addEventListener('resize', setLW); }

  // map each screen layer onto its quad in the device photo (4-point homography -> matrix3d)
  const solve = (src, dst) => {
    const A = [], b = [];
    for (let i = 0; i < 4; i++) { const [x, y] = src[i], [u, v] = dst[i];
      A.push([x, y, 1, 0, 0, 0, -u * x, -u * y]); b.push(u);
      A.push([0, 0, 0, x, y, 1, -v * x, -v * y]); b.push(v); }
    const n = 8, M = A.map((r, i) => [...r, b[i]]);
    for (let c = 0; c < n; c++) { let p = c; for (let r = c + 1; r < n; r++) if (Math.abs(M[r][c]) > Math.abs(M[p][c])) p = r; [M[c], M[p]] = [M[p], M[c]];
      for (let r = 0; r < n; r++) if (r !== c) { const f = M[r][c] / M[c][c]; for (let k = c; k <= n; k++) M[r][k] -= f * M[c][k]; } }
    const hh = M.map((r, i) => r[n] / r[i]);
    const [a, b2, c2, d, e2, f, g, h2] = hh;
    return 'matrix3d(' + [a, d, 0, g, b2, e2, 0, h2, 0, 0, 1, 0, c2, f, 0, 1].join(',') + ')';
  };
  const fit = () => {
    const rig = document.getElementById('rig'); if (!rig) return;
    const sc = rig.offsetWidth / 1787; if (!sc) return; rig.style.setProperty('--rigw', rig.offsetWidth + 'px');
    rig.querySelectorAll('.glass').forEach(g => {
      const q = g.dataset.quad.split(' ').map(p => p.split(',').map(Number).map(v => v * sc));
      g.style.transform = solve([[0, 0], [g.offsetWidth, 0], [g.offsetWidth, g.offsetHeight], [0, g.offsetHeight]], q);
      g.classList.add('ready');
    });
  };
  const rigEl = document.getElementById('rig');
  if (rigEl) { new ResizeObserver(fit).observe(rigEl); const shot = rigEl.querySelector('.shot'); if (shot.complete) fit(); else shot.addEventListener('load', fit);
    addEventListener('load', fit); let tries = 0; const retry = () => { fit(); if (!rigEl.querySelector('.glass.ready') && tries++ < 60) requestAnimationFrame(retry); }; requestAnimationFrame(retry); }


  // ===== hand-off + features + use-cases morph, one eased loop =====
  (() => {
    const cine=document.getElementById('cine'), hero=document.getElementById('hero'), rig=document.getElementById('rig'), feat=document.getElementById('features'), lap=document.getElementById('fbLap'), pin=document.getElementById('fbPin');
    if(!cine||!rig||!lap) return;
    const cards=[...feat.querySelectorAll('.fb-card')], wins=cards.map(c=>c.querySelector('.fb-frame')), tail=document.getElementById('fbTail'), tw=document.getElementById('fbTailwrap'), tailPane=document.getElementById('fbTailPane'), idlePane=document.getElementById('fbIdle'), lst=document.getElementById('fbList'), ul=lst.querySelector('ul');
    const ucSec=document.getElementById('use-cases');
    const clamp=(v,a,b)=>Math.min(b,Math.max(a,v)), ease=t=>1-Math.pow(1-t,3), lerp=(a,b,t)=>a+(b-a)*t, sm=t=>{t=clamp(t,0,1);return t*t*(3-2*t)};
    const LAP=[40,30,1390,860], BZ=[158,25,1276,782];
    const items=[...lst.querySelectorAll('li')];
    const pick=li=>{ items.forEach(x=>x.classList.toggle('on',x===li)); const k=items.indexOf(li); document.querySelectorAll('#fbTailPane .fb-tshot').forEach((s,i)=>s.classList.toggle('on',i===k)); const t=li.dataset.t, s=li.dataset.s.split(' \u00b7 '); { const _e=tailPane.querySelector('#tpT'); if(_e) _e.textContent=t; } { const _e=tailPane.querySelector('#tpTag'); if(_e) _e.textContent=li.dataset.tag; } { const _e=tailPane.querySelector('#tpS'); if(_e) _e.textContent=s[0]; } { const _e=tailPane.querySelector('#tpD'); if(_e) _e.textContent=s.slice(1).join(' \u00b7 ')||'\u2014'; } { const _e=tailPane.querySelector('#tpP'); if(_e) _e.textContent=li.dataset.tag; } };
    items.forEach(li=>li.addEventListener('click',()=>pick(li))); pick(items[0]);
    let cur={g:1,show:0}; const memo=new Map(); const sv=(el,k,v)=>{ const id=(el.id||el.className)+'|'+k; if(memo.get(id)===v) return; memo.set(id,v); if(k.startsWith('--')) el.style.setProperty(k,v); else el.style[k]=v; };
    const wind=hero.querySelector('.wind');
    const tick=()=>{
      const vh=innerHeight, vw=innerWidth; if(!vh||!rig.offsetWidth) return;
      const cr=cine.getBoundingClientRect(); const prog=Math.max(0,-cr.top/vh);
      const fade=clamp(prog/.5,0,1);
      sv(hero,'--fade',(1-fade).toFixed(3)); sv(hero,'--sc',(1-.15*fade).toFixed(4));
      const ink=clamp((prog-.1)/.6,0,1); sv(hero,'--ink',ink.toFixed(3)); const th=ink>.5?'dark':'light'; if(hero.dataset.theme!==th) hero.dataset.theme=th;
      
      const ph=hero.querySelector('.phglass'); if(ph){ ph.style.opacity=String(1-fade); ph.style.visibility=fade>=1?'hidden':''; }
      // untransformed target = the fixed laptop's resting rect
      const pr=pin.getBoundingClientRect(); const T={left:pr.left+lap.offsetLeft, top:pr.top+lap.offsetTop-lap.offsetHeight/2, width:lap.offsetWidth, height:lap.offsetHeight}; const Tcx=T.left+T.width/2, Tcy=T.top+T.height/2;
      // hero laptop travels onto it
      const sc=rig.offsetWidth/1787; const par=rig.parentElement.getBoundingClientRect(); const ox=par.left+rig.offsetLeft, oy=par.top+rig.offsetTop;
      const lcx=(LAP[0]+LAP[2])/2*sc, lcy=(LAP[1]+LAP[3])/2*sc, bw=(BZ[2]-BZ[0])*sc, bcx=(BZ[0]+BZ[2])/2*sc, bcy=(BZ[1]+BZ[3])/2*sc;
      const s=T.width/bw, e=ease(clamp((prog-.08)/.72,0,1)), S=lerp(1,s,e);
      const tx=lerp(0,Tcx-(ox+lcx)-(bcx-lcx)*s,e), ty=lerp(0,Tcy-(oy+lcy)-(bcy-lcy)*s,e);
      rig.style.transformOrigin=lcx+'px '+lcy+'px'; rig.style.transform='translate('+tx.toFixed(2)+'px,'+ty.toFixed(2)+'px) scale('+S.toFixed(4)+')';
      const hand=clamp((prog-.8)/.2,0,1); sv(rig,'opacity',(1-hand).toFixed(3)); if(wind){ const hv=cr.bottom>0&&ink<.98; if(hv&&wind.paused) wind.play().catch(()=>{}); else if(!hv&&!wind.paused) wind.pause(); } const lg=rig.querySelector('.lapglass'); if(lg) lg.style.visibility=hand>.5?'hidden':'';
      // intro: fades into the band once the laptop has parked, leaves with its wrapper
      const intro=document.getElementById('fbIntro'); const introIn=clamp((prog-.6)/.3,0,1); intro.style.setProperty('--io',introIn.toFixed(3));
      // features ground + tail
      const twr=tw.getBoundingClientRect(); const tailTop=(vh-tail.offsetHeight)/2;
      const sky=document.getElementById('fbSky'); const fr=feat.getBoundingClientRect(); const ucr=ucSec.getBoundingClientRect(); const prr=document.getElementById('pricing').getBoundingClientRect(); const whyR=document.getElementById('why').getBoundingClientRect(); const skyOn=ink>.02&&whyR.bottom>0; const skyOut=clamp((vh*.5-ucr.bottom)/(vh*.5)+1,0,1); sky.classList.toggle('on',skyOn); const bv=sky.querySelector('video.boxes'); if(bv){ if(skyOn&&bv.paused) bv.play().catch(()=>{}); else if(!skyOn&&!bv.paused) bv.pause(); } const lightIn=clamp((vh*.5-prr.top-vh*.8)/(vh*.6),0,1)*clamp((prr.bottom-vh*.5)/(vh*.8),0,1); const inWhy=clamp((vh*.6-whyR.top)/(vh*.6),0,1); sv(sky,'--sky',(ink*cur.g*(1-.86*lightIn)*(1-.75*inWhy)).toFixed(3));
      const t=performance.now()/1000; const par2=clamp((vh-fr.top)/(Math.max(1,fr.height)+vh),0,1); sv(sky,'--bx',(Math.sin(t*.11)*10).toFixed(2)); sv(sky,'--by',(-par2*60+Math.cos(t*.09)*8).toFixed(2));
      const light=0; let g=1; if(!isFinite(g)) g=1; cur.g+=(g-cur.g)*.14; if(Math.abs(g-cur.g)<.002) cur.g=g; if(!isFinite(cur.g)) cur.g=1; sv(feat,'--g',cur.g.toFixed(3)); sv(feat,'--tail-ink',light>.5?'#111315':'#F2F4F8');
      const ur=ucSec.getBoundingClientRect(); sv(tail,'opacity',(1-sm((-(ur.top)+vh*.35)/(vh*.45))).toFixed(3)); const over=Math.max(0,ul.scrollHeight-(lst.clientHeight-40)); const trr=tail.getBoundingClientRect(); const p=clamp((trr.top-twr.top)/Math.max(1,twr.height-tail.offsetHeight),0,1); { const tv='translateY('+(-over*p).toFixed(1)+'px)'; if(ul.style.transform!==tv) ul.style.transform=tv; }
      // cards: reveal + screen windows at the laptop's rect
      { let act=0; cards.forEach((c,i)=>{ if(c.getBoundingClientRect().top<=T.top+T.height*.5) act=i; }); const ds=lap.querySelectorAll('.fb-demo'); const anyCard=cards[0].getBoundingClientRect().top<=T.top+T.height*.5 && cards[2].getBoundingClientRect().bottom>T.top+T.height*.5; ds.forEach((d,i)=>d.classList.toggle('on',anyCard&&i===act)); }
      cards.forEach((c,i)=>{ const b=c.getBoundingClientRect(); c.classList.toggle('in',b.top<vh*.75); const tl=(T.left-b.left).toFixed(1)+'px', tt=T.top.toFixed(1)+'px', tw=T.width.toFixed(1)+'px', th=T.height.toFixed(1)+'px'; if(c.style.getPropertyValue('--tl')!==tl) c.style.setProperty('--tl',tl); if(c.style.getPropertyValue('--tt')!==tt) c.style.setProperty('--tt',tt); if(c.style.getPropertyValue('--tw')!==tw){ c.style.setProperty('--tw',tw); c.style.setProperty('--th',th); } });
      const first=cards[0].getBoundingClientRect(), lastB=cards[2].getBoundingClientRect().bottom;
      const tailOn=lastB<T.top+6; tailPane.classList.toggle('on',tailOn);
      idlePane.classList.toggle('on',hand>.5&&!tailOn&&first.top>T.top+6);
      // laptop shrinks into the dial's core dot
      const q=clamp((-ur.top)/(vh*1.0),0,1); // rise: 0 when the stage pins, 1 one screen later
      let show=hand*(1-sm((q-.8)/.2));
      const core=ucSec.querySelector('.core'), dialEl=document.getElementById('dial'), colEl=ucSec.querySelector('.col'), orbitEl=ucSec.querySelector('.orbit');
      let restCx=0, restCy=0;
      if(dialEl){ const rising=q<1; dialEl.classList.toggle('rising',rising);
        // rings: start as a circle hugging the laptop, centred on it, then travel to the dial's resting place
        const D=dialEl.offsetWidth||1; const wr=dialEl.parentElement.getBoundingClientRect(); restCx=wr.left+Math.max(18,Math.min(48,vw*.034))+Math.min(vh*.38,vw*.22); restCy=wr.top+wr.height/2;
        const hug=(Math.hypot(T.width,T.height)*1.12)/D; const e2=ease(q);
        sv(dialEl,'--ds',lerp(hug,1,e2).toFixed(4)); sv(dialEl,'--dx',lerp(Tcx-restCx,0,e2).toFixed(1)); sv(dialEl,'--dy',lerp(Tcy-restCy,0,e2).toFixed(1));
        sv(dialEl,'--rise',sm(q/.25).toFixed(3)); if(core) core.style.setProperty('--crise',sm((q-.15)/.25).toFixed(3)); sv(dialEl,'--draw',sm(q/.5).toFixed(3)); sv(dialEl,'--draw2',sm((q-.5)/.4).toFixed(3));
        sv(colEl,'--colin',sm((q-.55)/.4).toFixed(3)); sv(orbitEl,'--popin',sm((q-.7)/.3).toFixed(3)); }
      if(q>0&&core){ const ccx=restCx, ccy=restCy; const e3=sm(clamp((q-.2)/.8,0,1)); const k=lerp(1,Math.max(.02,26/T.width),e3); const dx=lerp(0,ccx-Tcx,e3), dy=lerp(0,ccy-Tcy,e3); lap.style.transform='translate('+dx.toFixed(1)+'px,'+dy.toFixed(1)+'px) scale('+k.toFixed(4)+')'; sv(core,'--core',sm((q-.75)/.2).toFixed(3)); sv(core,'--cores',lerp(2.4,1,sm((q-.75)/.25)).toFixed(3)); }
      else { lap.style.transform=''; }
      if(!isFinite(show)) show=0; cur.show+=(show-cur.show)*.2; if(Math.abs(show-cur.show)<.002) cur.show=show; if(!isFinite(cur.show)) cur.show=0; sv(lap,'opacity',cur.show.toFixed(3)); sv(lap,'visibility',cur.show>.01?'visible':'hidden');
    };
    const loop=()=>{ try{ tick(); }catch(err){ if(!window.__tickErr){ window.__tickErr=String(err&&err.stack||err); console.error('tick',err); } } requestAnimationFrame(loop); }; loop(); addEventListener('scroll',()=>{ try{ tick(); }catch(e){} },{passive:true});
  })();

  // hero demo stepper
  const N = 4, DUR = 3200; let cur = -1, timer = 0;
  const setStep = (i, user) => {
    cur = (i + N) % N;
    document.querySelectorAll('.frames .frame').forEach(f => { const k = +f.dataset.i; f.classList.toggle('on', k === cur); f.classList.toggle('was', k < cur); });
    document.querySelectorAll('.card.fly').forEach(c => { const k = +c.dataset.i; c.classList.toggle('on', k === cur); c.classList.toggle('old', k < cur); });
    document.querySelectorAll('.dots button').forEach(b => b.setAttribute('aria-selected', +b.dataset.i === cur));
    clearInterval(timer); if (!reduce) timer = setInterval(() => setStep(cur + 1), user ? DUR * 1.5 : DUR);
  };
  document.querySelectorAll('.dots button').forEach(b => b.addEventListener('click', () => setStep(+b.dataset.i, true)));
  let sx = 0; const stage = document.querySelector('.stage');
  stage.addEventListener('pointerdown', e => { sx = e.clientX; });
  stage.addEventListener('pointerup', e => { const dx = e.clientX - sx; if (Math.abs(dx) > 40) setStep(cur + (dx < 0 ? 1 : -1), true); });
  setTimeout(() => setStep(0), 900);
})();

(() => {
  const N=4, sec=document.getElementById('use-cases'), dial=document.getElementById('dial'), core=document.getElementById('core'), stops=document.getElementById('stops'), items=[...document.querySelectorAll('.ucd .item')], pops=[...document.querySelectorAll('.pop')], prog=[];
  // stops on the inner ring, evenly spaced; active stop is rotated to 3 o'clock
  const STEP=30; // stops along the visible arc
  const ang=i=>-45+i*STEP;
  items.forEach((it,i)=>{ const a=ang(i)*Math.PI/180; const x=200+140*Math.cos(a), y=200+140*Math.sin(a);
    stops.insertAdjacentHTML('beforeend',`<circle class="stop" data-i="${i}" cx="${x}" cy="${y}" r="3.2"/><circle class="halo" cx="${x}" cy="${y}" r="7"/>`); });
  [-100,-76,72,96,118].forEach(d=>{ const a=d*Math.PI/180; stops.insertAdjacentHTML('beforeend',`<circle class="stop dim" cx="${200+140*Math.cos(a)}" cy="${200+140*Math.sin(a)}" r="1.7"/>`); });
  const stopEls=[...stops.querySelectorAll('.stop:not(.dim)')];
  // popup layout per active index: [x,y,scale,opacity,blur,width]
  const orbit=document.getElementById('orbit');
  const radius=()=>Math.min(dial.offsetWidth*.5*1.18, innerHeight*.38);
  const clamp=(v,a,b)=>Math.min(b,Math.max(a,v)), sm=t=>{t=clamp(t,0,1);return t*t*(3-2*t)};
  let active=-1, manual=-1, curC=0, tgtC=0, lastRR=0;
  const mark=i=>{ active=i; items.forEach((it,k)=>it.classList.toggle('on',k===i)); stopEls.forEach((st,k)=>st.classList.toggle('on',k===i)); pops.forEach((p,k)=>p.classList.toggle('on',k===i)); };
  items.forEach(it=>it.addEventListener('click',()=>{ manual=+it.dataset.i; manualY=scrollY; }));
  let manualY=0; addEventListener('scroll',()=>{ if(manual>=0&&Math.abs(scrollY-manualY)>innerHeight*.25) manual=-1; },{passive:true});
  function tick(){ const r=sec.getBoundingClientRect(); const total=r.height-innerHeight-innerHeight; if(!innerHeight||total<=0||!dial.offsetWidth){ requestAnimationFrame(tick); return; }
    const p=clamp((-r.top-innerHeight)/total,0,1); const x=p*N; const idx=Math.min(N-1,Math.floor(x+.0001)); const sub=clamp(x-idx,0,1);
    // dwell on each stop, turn during the last 35% of it
    let c=Math.min(N-1, idx+sm((sub-.65)/.35));
    if(manual>=0) c=manual;
    tgtC=c; curC+=(tgtC-curC)*.12; if(Math.abs(tgtC-curC)<.0005) curC=tgtC;
    const rot=-(ang(curC)); const rr=radius();
    dial.style.setProperty('--rot',rot.toFixed(3)); orbit.style.setProperty('--rot',rot.toFixed(3));
    pops.forEach((pp,k)=>{ const d=Math.abs(k-curC); const s=1-.38*clamp(d/1.3,0,1), o=1-.62*clamp(d/1.1,0,1); if(rr!==lastRR){ pp.style.setProperty('--a',ang(k)); pp.style.setProperty('--rr',rr); } pp.style.setProperty('--rot',rot.toFixed(3)); const rel=k-curC; const a2=rel*30-rot; pp.style.setProperty('transform','rotate('+a2.toFixed(3)+'deg) translate('+rr+'px) rotate('+(-(a2+rot)).toFixed(3)+'deg) scale('+s.toFixed(4)+')','important'); pp.style.setProperty('--ps',s.toFixed(4)); pp.style.setProperty('--po',o.toFixed(3)); pp.style.zIndex=String(10-Math.round(d*3)); });
    lastRR=rr;
    mark(Math.round(curC));
    items.forEach((it,k)=>it.style.setProperty('--sub',k===idx?sub.toFixed(3):k<idx?1:0));
    
    requestAnimationFrame(tick); }
  tick();
})();
(() => {
const PLANS=[
  {id:'free',name:'Free',limit:150,m:0,lim:'Stock quantity up to 150 · 1 user · 1 location',feat:['Order tracking and alerts','Basic returns and exchanges','Piece-level stock tracking','Warehouse flow, up to 150 in stock']},
  {id:'plus',name:'Plus',limit:1000,m:999,lim:'Stock quantity up to 1,000 · 2 users · 1 location',feat:['Everything in Free, plus','Warehouse flow','Bazar POS mode','Collected revenue']},
  {id:'pro',name:'Pro',limit:5000,m:1199,lim:'Stock quantity up to 5,000 · 5 users · 3 locations',feat:['Everything in Plus, plus','Advanced returns and exchanges','Revenue breakdown','Advanced insights']},
  {id:'max',name:'Max',limit:Infinity,m:2499,lim:'Unlimited stock quantity, users and locations',feat:['Everything in Pro, plus','Unlimited stock quantity','Unlimited users and locations']}
];
const TIP={"Order tracking and alerts":"See where every order is, from the moment it's placed to the customer's door. Get alerted when an order is late, stuck or refused.","Basic returns and exchanges":"Customers request returns or size swaps from a simple page. You approve, the courier picks it up, you scan it back in and it's restocked. Refunds are recorded.","Advanced returns and exchanges":"Everything in Basic, plus: your logo and colours on the returns page, customers tell you why they're returning, they can upload photos, track their return, and exchange for a different product.","Piece-level stock tracking":"Every piece gets its own barcode. Always know how many you have, where each piece is, and your Shopify stock stays accurate.","Warehouse flow":"Receive new stock, print barcodes, pick and pack orders by scanning, hand over to the courier with a printed list, and count your stock anytime.","Bazar POS mode":"Take pieces to a bazaar and sell by scanning with your phone. Your website updates instantly, and at the end you'll know exactly what sold and what's left.","Collected revenue":"The money that actually reached you, not what Shopify shows. Refused orders, returns and cash your courier still holds are all taken out.","Revenue breakdown":"See where your money goes between order and cash: cancellations, refusals, returns and shipping costs. Compare website vs bazaar sales.","Advanced insights":"A step-by-step view of your revenue, from orders placed to cash in hand, showing exactly where money drops off. Plus: which products get returned most and why, which areas refuse orders most, how fast your courier delivers, and how many customers come back.","Teams and locations":"Give your team their own logins and manage stock across more than one warehouse or showroom."};
const ROWS=[
  ['Stock quantity<span class="info" tabindex="0" aria-label="Total units across all products, not SKUs">i<em>Total units across all products, not SKUs</em></span>',['150','1,000','5,000','Unlimited'],1],
  ['Order tracking and alerts',[1,1,1,1]],
  ['Basic returns and exchanges',[1,1,1,1]],
  ['Piece-level stock tracking',['Up to 150','Up to 1,000','Up to 5,000','Unlimited']],
  ['Warehouse flow',['Up to 150',1,1,1]],
  ['Advanced returns and exchanges',[0,0,1,1]],
  ['Bazar POS mode',[0,1,1,1]],
  ['Collected revenue',[0,1,1,1]],
  ['Revenue breakdown',[0,0,1,1]],
  ['Advanced insights',[0,0,1,1]],
  ['Teams and locations',['1 user, 1 location','2 users, 1 location','5 users, 3 locations','Unlimited']]
];
let bill='m';
const fmt=n=>'EGP '+Math.round(n).toLocaleString('en-EG');
const priceOf=p=>bill==='y'?Math.round(p.m*10/12):p.m;
const recId=()=>window.__rec||'pro';
const renderPlans=()=>{ const rec=recId();
  document.querySelectorAll('[data-plans]').forEach(host=>{
    host.innerHTML=PLANS.map(p=>{ const pr=p.m?`<div class="price">${fmt(priceOf(p))}<small> / month</small>${bill==='y'?'<span class="was">'+fmt(p.m*10)+' billed yearly</span>':''}</div>`:`<div class="price">Free<small></small></div>`;
      return `<div class="plan" data-plan="${p.id}"><span class="tag"><i class="dotslot"></i>For your numbers</span><h3>${p.name}</h3>${pr}<div class="lim">${p.lim}</div><ul>${p.feat.map(f=>/, plus$/.test(f)?'<li class="plus">'+f+'</li>':'<li><i></i>'+f+'</li>').join('')}</ul><a class="pill ${p.id===rec?'':'q'}" href="https://app.tracedtech.com/signup">${p.m?'Start '+p.name:'Start free'}</a></div>`; }).join('');
    host.querySelectorAll('.plan').forEach(p=>p.classList.toggle('rec',p.dataset.plan===rec)); });
  const ri=PLANS.findIndex(p=>p.id===rec);
  document.querySelectorAll('[data-cmp]').forEach(t=>{
    const cell=(v,i)=>{ const c=i===ri?' rc':''; if(v===1) return `<td class="y${c}">✓</td>`; if(v===0) return `<td class="n${c}">–</td>`; return `<td class="${c.trim()}">${v}</td>`; };
    t.innerHTML=`<thead><tr><th>Feature</th>${PLANS.map((p,i)=>`<th class="${i===ri?'rc':''}"><i class="hslot"></i>${p.name}</th>`).join('')}</tr></thead><tbody><tr class="key"><td>Price / month</td>${PLANS.map((p,i)=>cell(p.m?fmt(priceOf(p)):'0',i)).join('')}</tr>${ROWS.map(r=>`<tr class="${r[2]?'key':''}"><td>${r[0]}${TIP[r[0]]?'<span class="info" tabindex="0">i<em>'+TIP[r[0]]+'</em></span>':''}</td>${r[1].map(cell).join('')}</tr>`).join('')}</tbody>`; });
};
renderPlans();
document.getElementById('bill').addEventListener('click',e=>{ const b=e.target.closest('button'); if(!b) return; bill=b.dataset.b; document.querySelectorAll('#bill button').forEach(x=>x.classList.toggle('on',x===b)); renderPlans(); document.querySelectorAll('[data-calc] input[type=range]').forEach(i=>i.dispatchEvent(new Event('input'))); });
document.querySelectorAll('[data-calc]').forEach(sec=>{
  const get=k=>+sec.querySelector(`[data-i="${k}"]`).value;
  const set=(k,v)=>sec.querySelectorAll(`[data-o="${k}"]`).forEach(el=>el.innerHTML=v);
  let shown=0, tgt=0, ticking=false; const numEl=sec.querySelector('[data-o="total"]');
  const count=n=>{ tgt=n; if(ticking) return; ticking=true; const step=()=>{ shown+=(tgt-shown)*.16; if(Math.abs(tgt-shown)<1){ shown=tgt; ticking=false; } numEl.textContent=fmt(shown); if(ticking) requestAnimationFrame(step); }; step(); };
  const run=()=>{ const o=get('orders'), v=get('value'), r=get('ret'), l=get('lost'), rc=get('rec'), dg=get('dmg');
    const pieces=o*1.4;
    const v1=o*(l/100)*v*(rc/100);           // 60% of quiet parcels proven and recovered
    const v2=o*(r/100)*(dg/100)*v;          // 8% of returns would have shipped unwashed
    const total=v1+v2; const plan=PLANS.find(p=>pieces<=p.limit); const price=priceOf(plan);
    const payDays=price?Math.max(1,Math.ceil(price/(total/30))):0; const changed=window.__rec!==plan.id; window.__rec=plan.id; if(changed) renderPlans();
    sec.querySelectorAll('input[type=range]').forEach(i=>i.style.setProperty('--p',((i.value-i.min)/(i.max-i.min)*100)+'%'));
    set('orders',o.toLocaleString()); set('value',fmt(v)); set('ret',r+'%'); set('lost',l+'%'); set('rec',rc+'%'); set('dmg',dg+'%');
    set('ordersBig',o.toLocaleString()+'<small>orders / mo</small>'); set('pieces',Math.round(pieces).toLocaleString());
    set('v1',fmt(v1)); set('v2',fmt(v2));
    count(total); set('multLine',price?'<b>'+Math.max(1,Math.round(total/price))+'×</b> the price of Traced '+plan.name+'.':'Traced <b>Free</b> covers your stock.');
    set('pay',price?payDays+(payDays===1?' day':' days'):'day one');
    sec.querySelectorAll('.plan').forEach(p=>p.classList.toggle('rec',p.dataset.plan===plan.id));
  };
  sec.querySelectorAll('input').forEach(i=>i.addEventListener('input',run)); run();
});
  // the number arrives centred, then settles into its column as the sliders fade in
  const stage=document.getElementById('prStage'), out=document.getElementById('prOut'), grid=stage.querySelector('.pr-grid'), ctl=stage.querySelector('.ctl');
  const clamp=(v,a,b)=>Math.min(b,Math.max(a,v)), sm=t=>{t=clamp(t,0,1);return t*t*(3-2*t)}, ease=t=>1-Math.pow(1-t,3);
  const ch=()=>{ const r=grid.getBoundingClientRect(), vh=innerHeight; const t=clamp((vh*.95-r.top)/(vh*.75),0,1); const e=ease(t);
    const gr=grid.getBoundingClientRect(), orr=out.getBoundingClientRect(); const cx=gr.left+gr.width/2-(orr.left-parseFloat(out.style.getPropertyValue('--px')||0)); // centre offset in untransformed space
    out.style.setProperty('--po',sm(t/.4).toFixed(3)); out.style.setProperty('--ps',(1.18-.18*e).toFixed(4)); out.style.setProperty('--px',((1-e)*(cx-orr.width/2)*-1).toFixed(1)); out.style.setProperty('--py',((1-e)*-40).toFixed(1));
    ctl.classList.toggle('in',t>.55); requestAnimationFrame(ch); };
  ch();
  // reveal
  const io=new IntersectionObserver(es=>es.forEach(e=>{ if(e.isIntersecting){ e.target.classList.add('in'); io.unobserve(e.target); } }),{threshold:.2});
  document.querySelectorAll('.pr-in:not(.ctl), .ig-in, .fq-in').forEach(el=>io.observe(el)); document.querySelectorAll('.fq-card').forEach((c,i)=>c.style.setProperty('--i',i%2?1:0));
  // the travelling dot: dial centre -> pricing figure -> plan -> globe nucleus -> globe contracts -> thread draws down -> underline
  (() => {
    const dot=document.getElementById('tdot'), uc=document.getElementById('use-cases'), pr=document.getElementById('pricing'), ig=document.getElementById('integrations'), why=document.getElementById('why'), globe=document.getElementById('igGlobe'), dial=document.getElementById('dial'), core=uc.querySelector('.core'), tl=document.getElementById('tline'), tp=document.getElementById('tlinePath');
    if(!dot||!uc||!pr||!ig||!why) return;
    const clamp=(v,a,b)=>Math.min(b,Math.max(a,v)), sm=t=>{t=clamp(t,0,1);return t*t*(3-2*t)}, lerp=(a,b,t)=>a+(b-a)*t;
    const C=el=>{ const r=el.getBoundingClientRect(); return {x:r.left+r.width/2,y:r.top+r.height/2,w:r.width,h:r.height,l:r.left}; };
    const sv=(el,k,v)=>{ if(el.style[k]!==v) el.style[k]=v; };
    const put=(x,y,op,sz=26)=>{ sv(dot,'transform','translate('+(x-sz/2).toFixed(1)+'px,'+(y-sz/2).toFixed(1)+'px)'); sv(dot,'width',sz.toFixed(1)+'px'); sv(dot,'height',sz.toFixed(1)+'px'); sv(dot,'opacity',op.toFixed(3)); sv(dot,'visibility',op>.01?'visible':'hidden'); };
    let lastD='';
    const tick=()=>{
      const vh=innerHeight, vw=innerWidth, HOLD=vh*.2;
      const ucr=uc.getBoundingClientRect(), prr=pr.getBoundingClientRect(), igr=ig.getBoundingClientRect(), whr=why.getBoundingClientRect();
      const A0=dial?C(dial):{x:vw*.3,y:vh/2}; let A1=C(document.getElementById('prDot')); const recSlot=pr.querySelector('.plan.rec .dotslot')||pr.querySelector('.plan .dotslot'); let A2=recSlot?C(recSlot):A1; const G=globe?C(globe):{x:vw*.25,y:vh/2,w:600};
      const hold=a=>({...a,y:Math.max(a.y,HOLD)}); A1=hold(A1); A2=hold(A2);
      // 0. leave the dial: the fixed dot takes over from the dial's core as the stage unpins
      const leave=sm((vh-ucr.bottom)/(vh*.3)); if(core) core.style.setProperty('--cleave',String(1-leave));
      // 1. dial -> figure -> plan
      const t01=sm((vh*.6-prr.top)/(vh*1.2)); const plans=pr.querySelector('.plans'); const t12=plans?sm((vh*.85-plans.getBoundingClientRect().top)/(vh*.55)):0;
      // 2. plan -> globe nucleus (dot keeps its size; the globe fades up around it)
      const hs=pr.querySelector('.cmp th.rc .hslot'); let A3=hs?C(hs):A2; A3=hold(A3); const cmpT=pr.querySelector('.cmp'); const t2h=cmpT?sm((vh*.8-cmpT.getBoundingClientRect().top)/(vh*.45)):0;
      const t23=sm((vh*.9-igr.top)/(vh*.9));
      // 3. globe contracts onto the dot once its centre has risen past mid-screen
      const tc=sm((vh*.5-G.y)/(vh*.28));
      // 4. thread draws from the hold point down to the underline as Why arrives
      const t34=sm((vh*1.0-whr.top)/(vh*1.0));
      let x,y,op=leave;
      // dial -> the strip's rail: the dot draws the rail from left to right, then leaves from its right end
      const rail=document.getElementById('tsRail'), ts=document.getElementById('brands'), av=document.getElementById('avDot'), ul=ts&&ts.querySelector('.ul'); let R0=A0, dsz=26, tsThread=false;
      if(rail&&av&&ul){ const tsr=ts.getBoundingClientRect(); const pT=clamp((-tsr.top-vh*.5)/(tsr.height-vh*1.5),0,1); // 0 when the scene pins, 1 when it releases
        const rr=rail.getBoundingClientRect(), ar=C(av), ur=ul.getBoundingClientRect();
        const tin=sm((vh*.9-(tsr.top+vh*.5))/(vh*.4)); ts.style.setProperty('--tsin',String(tin));
        // 1. park as the decimal point in 5.0
        const tP=sm(tin>0?(tin*.6+pT*2):0); const parkY=Math.max(ar.y+ar.h*.15,HOLD);
        R0={x:lerp(A0.x,ar.x,tP),y:lerp(A0.y,parkY,tP)+Math.sin(tP*Math.PI)*-70}; dsz=lerp(26,9,tP);
        // 2. underline draws under "first in"
        const tU=sm((pT-.12)/.22); ts.style.setProperty('--ul',String(tU));
        // 3. thread continues from the underline's right end to the rail's left end; 4. rail draws
        const tT=sm((pT-.36)/.3); const railY=rr.top+1;
        if(tT>0&&tT<1){ const ulp=ul.querySelector('svg').getBoundingClientRect(); const sx=ulp.right-2, sy=ulp.top+ulp.height*.55; const ex=rr.left, ey=railY; const d='M'+sx.toFixed(1)+' '+sy.toFixed(1)+' C '+(sx+vw*.12).toFixed(1)+' '+(sy+(ey-sy)*.45).toFixed(1)+', '+(ex+vw*.3).toFixed(1)+' '+(sy+(ey-sy)*.8).toFixed(1)+', '+ex.toFixed(1)+' '+ey.toFixed(1); if(d!==lastD){ tp.setAttribute('d',d); lastD=d; } const L=tp.getTotalLength(); tp.style.strokeDasharray=L+' '+L; tp.style.strokeDashoffset=String(L*(1-tT)); sv(tl,'transition','none'); sv(tl,'opacity','1'); tsThread=true; }
        else if(tT>=1&&pT<.92){ sv(tl,'opacity','1'); tsThread=true; }
        rail.style.setProperty('--rail',String(tT>=1?1:0)); rail.style.setProperty('--railx',String(tT>=1?1:0)); }
      if(t12>0){ x=lerp(A1.x,A2.x,t12); y=lerp(A1.y,A2.y,t12)+Math.sin(t12*Math.PI)*-50; } else { x=lerp(R0.x,A1.x,t01); y=lerp(R0.y,A1.y,t01)+Math.sin(t01*Math.PI)*-80; }
      if(t2h>0){ x=lerp(A2.x,A3.x,t2h); y=lerp(A2.y,A3.y,t2h)+Math.sin(t2h*Math.PI)*-30; }
      if(t23>0){ const S=t2h>0?A3:A2; const gy=Math.max(G.y,HOLD); x=lerp(S.x,G.x,t23); y=lerp(S.y,gy,t23)+Math.sin(t23*Math.PI)*-60; }
      dot.style.setProperty('--dz', (t23>=1 && t34<=0) ? '2' : '7');
      if(globe){ sv(globe,'opacity',String(1*sm((t23-.5)/.5)*(1-tc))); globe.style.setProperty('--gs',String(1-.96*tc)); }
      // thread
      if(t34>0){
        const wsvg=why.querySelector('#word svg'); const E=wsvg?C(wsvg):{x:vw/2,y:vh*.55,w:300,l:vw/2-150};
        const S={x:x,y:y}; const ex=E.l+E.w*.02, ey=E.y; // land at the underline's left end
        const d='M'+S.x.toFixed(1)+' '+S.y.toFixed(1)+' C '+(S.x+vw*.18).toFixed(1)+' '+(S.y+(ey-S.y)*.35).toFixed(1)+', '+(ex-vw*.22).toFixed(1)+' '+(S.y+(ey-S.y)*.7).toFixed(1)+', '+ex.toFixed(1)+' '+ey.toFixed(1)+' H '+(E.l+E.w).toFixed(1);
        if(d!==lastD){ tp.setAttribute('d',d); lastD=d; }
        const L=tp.getTotalLength(); const draw=sm(t34/.92); tp.style.strokeDasharray=L+' '+L; tp.style.strokeDashoffset=String(L*(1-draw));
        const P=tp.getPointAtLength(L*draw); x=P.x; y=P.y;
        const done=t34>=.96; sv(tl,'opacity',String(done?0:1)); why.classList.toggle('linked',done); op=done?0:1;
        if(!done) sv(tl,'transition','none'); else sv(tl,'transition','opacity .6s');
      } else { if(!tsThread) sv(tl,'opacity','0'); why.classList.remove('linked'); }
      if(ucr.bottom>vh+2) op=0;
      let size=(t01>0||t12>0||t23>0||t34>0)?lerp(dsz,26,Math.max(t01,t12,t23,t34)):dsz;
      if(t2h>0&&t23<1) size=lerp(26,12,t2h*(1-t23));
      put(x,y,op,size);
      requestAnimationFrame(tick); };
    tick();
  })();
  // brands strip
  (() => {
const Q=[
 ['BLNCO','assets/brand-blnco.png','Omar Aly · Founder, BLNCO','Setup took an afternoon. We labelled the whole stockroom and haven\'t lost a piece since.',5],
 ['Jumi','assets/brand-jumi.png','Mohamed Afyouni · Founder, Jumi','The scanner blocks anything on hold, so returns don\'t get reshipped by mistake anymore.',5],
 ['The Snouts','assets/brand-snouts.png','Mohamed Ghanem · Founder, The Snouts','Bosta claimed a parcel never reached the hub. I sent them the scan. Refunded in two days.',5],
 ['BLNCO','assets/brand-blnco.png','Omar Aly · BLNCO','Shopify stock finally matches what\'s actually on the shelf. Oversells stopped.',5],
 ['Jumi','assets/brand-jumi.png','Mohamed Afyouni · Jumi','My team scans from their phones, I see every move from the laptop. Simple.',5],
 ['The Snouts','assets/brand-snouts.png','Mohamed Ghanem · The Snouts','Wish we had this a year ago. Would have saved us thousands in write-offs.',5]
];
const card=(r,i)=>`<div class="tq"><div class="lg"><img src="${__img(r[1])}" alt="${r[0]}"></div><div><div class="top"><b>${r[0]}</b><span class="stars">${'★'.repeat(r[4])}</span></div><q>${r[3]}</q><div class="who">${r[2]}</div></div></div>`;
const fill=(el,arr)=>{ el.innerHTML=arr.map(card).join('')+arr.map(card).join(''); };
fill(document.getElementById('l1'),Q);
  })();
  // why traced
  (() => {
const BEATS=window.WHY_BEATS=[
 {w:'Proven',sub:'A courier says a parcel never arrived. You open the piece and show the scan: hub, name, 13:40. The conversation ends there.',
  cards:[
   {x:14,y:26,w:220,html:'<div class="wcard"><div class="h"><i></i><span class="t">TRC-0413 · Linen shirt · M</span><span class="tag">With courier</span></div><div class="row"><span>Handed to Bosta, Nasr City hub</span><b>Omar · 13:40</b></div><div class="row"><span>Out for delivery, Maadi</span><b>Bosta · 09:02</b></div></div>'},
   {x:12,y:70,w:190,img:'assets/uc-courier.png'},
   {x:84,y:22,w:230,html:'<div class="wcard dark"><div class="h"><i class="g"></i><span class="t">Bosta</span><span class="tag">Delivered</span></div><div class="row"><span>Signed by</span><b>Mariam A.</b></div><div class="row"><span>Scan</span><b>Tue 14:12</b></div></div>'},
   {x:86,y:68,w:210,html:'<div class="wcard"><div class="h"><i></i><span class="t">Claim</span><span class="tag">Sent</span></div><div class="big">EGP 1,200<small>recovered</small></div><div class="row"><span>Proof attached</span><b>3 scans</b></div></div>'},
   {x:50,y:12,w:200,html:'<div class="wcard dark"><div class="h"><i class="r"></i><span class="t">TRC-0391</span><span class="tag">49h without a scan</span></div><div class="row"><span>Last seen</span><b>Bosta hub</b></div></div>'},
   {x:50,y:90,w:230,html:'<div class="wcard"><div class="h"><i></i><span class="t">Every hand-off</span><span class="tag">Name · time</span></div><div class="row"><span>Received → Shipped → Delivered</span><b>3 scans</b></div></div>'}
  ]},
 {w:'Counted',sub:'What Shopify can sell is what is actually on the shelf, minus what is on hold or with a courier. No end-of-month recount.',
  cards:[
   {x:13,y:30,w:230,html:'<div class="wcard"><div class="h"><i></i><span class="t">Linen shirt · M</span><span class="tag">Synced 2 min ago</span></div><div class="big">4<small>available on Shopify</small></div><div class="row"><span>2 shelf · 1 courier · 1 hold</span><b>live</b></div></div>'},
   {x:14,y:74,w:180,html:'<div class="wcard"><div class="bar"></div><div class="code">TRC-0413 · LINEN SHIRT · M</div></div>'},
   {x:86,y:26,w:190,img:'assets/uc-stock.png'},
   {x:85,y:72,w:230,html:'<div class="wcard dark"><div class="h"><i class="g"></i><span class="t">Shelf B3</span><span class="tag">Counted</span></div><div class="row"><span>Pieces</span><b>14 of 14</b></div><div class="row"><span>By</span><b>Sara · Wed 10:05</b></div></div>'},
   {x:50,y:11,w:210,html:'<div class="wcard"><div class="h"><i></i><span class="t">Order #4821</span><span class="tag">3 labels printed</span></div><div class="row"><span>TRC-0413 · 0414 · 0415</span><b>Sara</b></div></div>'},
   {x:50,y:90,w:200,html:'<div class="wcard dark"><div class="h"><i></i><span class="t">Oversells this month</span><span class="tag">0</span></div></div>'}
  ]},
 {w:'Found',sub:'Returned, exchanged, at the cleaner, back on the shelf. Every piece tells you where it is, so nobody has to remember.',
  cards:[
   {x:14,y:28,w:190,img:'assets/uc-return.png'},
   {x:13,y:72,w:230,html:'<div class="wcard"><div class="h"><i class="r"></i><span class="t">TRC-0413 · Linen shirt · M</span><span class="tag" style="background:#FDF1F1;color:#B91C1C">Hold · not cleaned</span></div><div class="row"><span>Returned, size exchange</span><b>Sara · 11:02</b></div><div class="row"><span>Sent to Clean&amp;Co</span><b>Sara · 11:20</b></div></div>'},
   {x:86,y:24,w:230,html:'<div class="wcard dark"><div class="h"><i class="g"></i><span class="t">TRC-0413</span><span class="tag">Back in stock</span></div><div class="row"><span>Cleaned</span><b>Clean&amp;Co · Wed</b></div><div class="row"><span>Shelf</span><b>B3</b></div></div>'},
   {x:86,y:70,w:200,html:'<div class="wcard"><div class="h"><i></i><span class="t">Where is it?</span><span class="tag">Scan</span></div><div class="big">B3<small>shelf, 2nd row</small></div></div>'},
   {x:50,y:11,w:220,html:'<div class="wcard"><div class="h"><i></i><span class="t">Exchange #4830</span><span class="tag">Two pieces moving</span></div><div class="row"><span>Out: M → In: L</span><b>both traced</b></div></div>'},
   {x:50,y:90,w:200,html:'<div class="wcard dark"><div class="h"><i></i><span class="t">Pieces at the cleaner</span><span class="tag">3</span></div></div>'}
  ]}
];
const copy=document.querySelector('.wcopy'), sec=document.getElementById('scene'), whySec=document.getElementById('why');
// slot layout: 0,1 left gutter (upper/lower); 2,3 right gutter; 4 above copy; 5 below copy
function place(el,k,w){ const S=sec.getBoundingClientRect(), C=copy.getBoundingClientRect(); const cl=C.left-S.left, cr=C.right-S.left, ct=C.top-S.top, cb=C.bottom-S.top, W=S.width, H=S.height;
  const gL=cl, gR=W-cr; const gutter=Math.min(gL,gR); const fit=Math.max(150,Math.min(w, gutter-32)); el.style.setProperty('--w',fit); el.style.display=(k<4 && gutter<200)?'none':'';
  let x,y; const lx=gL/2, rx=cr+gR/2;
  if(k===0){x=lx;y=H*.3}else if(k===1){x=lx*1.1;y=H*.72}else if(k===2){x=rx;y=H*.28}else if(k===3){x=rx*.98;y=H*.72}else if(k===4){x=W/2;y=Math.max(60,ct/2)}else{x=W/2;y=Math.min(H-60,cb+(H-cb)/2)}
  if(k>=4 && (k===4? ct<150 : H-cb<150)) el.style.display='none';
  x=Math.max(16+fit/2, Math.min(W-16-fit/2, x));
  el.style.setProperty('--px',x.toFixed(0)); el.style.setProperty('--py',y.toFixed(0)); }
const field=document.getElementById('wfield'), word=document.getElementById('word'), sub=document.getElementById('sub'), dots=document.getElementById('wdots');
BEATS.forEach((b,i)=>{ const s=document.createElement('span'); s.textContent=b.w; word.appendChild(s); const d=document.createElement('i'); d.addEventListener('click',()=>{ const r=whySec.getBoundingClientRect(); const total=r.height-innerHeight; scrollTo({top:r.top+scrollY+total*((i+.5)/BEATS.length),behavior:'smooth'}); }); dots.appendChild(d); });
const ul=document.createElementNS('http://www.w3.org/2000/svg','svg'); ul.innerHTML='<path d="M4 14 C 60 4, 140 4, 200 12 S 300 18, 316 10"/>'; ul.setAttribute('viewBox','0 0 320 20'); ul.setAttribute('preserveAspectRatio','none'); word.appendChild(ul);
let cur=-1;
const bleed=document.querySelector('.why .bleed'); const CORNERS=[[0,0],[-24,-18],[-30,26]]; // field offset per beat, vw/vh
function go(i){ if(i===cur) return; cur=i; const b=BEATS[i]; const aur=document.getElementById('whyAur'); if(aur){ const P=[[0,0,1],[-3,-2,1.08],[3,2,1.04]][i]; aur.style.setProperty('--sx',P[0]); aur.style.setProperty('--sy',P[1]); aur.style.setProperty('--ss',P[2]); }
  [...word.querySelectorAll('span')].forEach((s,k)=>s.classList.toggle('on',k===i)); const p=ul.querySelector('path'); p.style.animation='none'; void p.offsetWidth; p.style.animation='';
  sub.textContent=b.sub; [...dots.children].forEach((d,k)=>d.classList.toggle('on',k===i));
  [...field.children].forEach(el=>{ el.classList.remove('on'); setTimeout(()=>el.remove(),900); });
  b.cards.forEach((c,k)=>{ const el=document.createElement('div'); el.className='pc'; el.dataset.k=k; el.dataset.w=c.w; place(el,k,c.w); el.style.setProperty('--d',k*1.7); el.style.setProperty('--a',10+(k%3)*6); el.innerHTML=c.img?`<img src="${__img(c.img)}" alt="">`:c.html; field.appendChild(el); requestAnimationFrame(()=>setTimeout(()=>el.classList.add('on'),80+k*90)); }); }
const clamp=(v,a,b)=>Math.min(b,Math.max(a,v));
function onScroll(){ const r=whySec.getBoundingClientRect(); const total=r.height-innerHeight; const av=document.getElementById('whyAur'); if(av){ const vis=r.bottom>0&&r.top<innerHeight; if(vis&&av.paused) av.play().catch(()=>{}); else if(!vis&&!av.paused) av.pause(); } if(total<=0) return; const p=clamp(-r.top/total,0,1); go(Math.min(BEATS.length-1,Math.floor(p*BEATS.length+.0001))); }
addEventListener('scroll',onScroll,{passive:true}); addEventListener('resize',()=>{ onScroll(); [...field.children].forEach(el=>place(el,+el.dataset.k,+el.dataset.w)); }); go(0); onScroll();
  })();
  // integrations: connector lines + spine, redrawn on resize
  const dg=document.getElementById('igDiagram'), sv=document.getElementById('igLines');
  const draw=()=>{ if(!dg||innerWidth<900){ if(sv) sv.innerHTML=''; return; } const D=dg.getBoundingClientRect(); sv.setAttribute('viewBox','0 0 '+D.width+' '+D.height); let out='';
    const tiles=[...dg.querySelectorAll('.ig-tile')], rows=[...dg.querySelectorAll('.ig-r')];
    const first=tiles[0].getBoundingClientRect(), last=tiles[tiles.length-1].getBoundingClientRect(); const sx=first.left+first.width/2-D.left;
    out+='<line class="spine" x1="'+sx+'" y1="'+(first.bottom-D.top)+'" x2="'+sx+'" y2="'+(last.top-D.top)+'"/>';
    tiles.forEach((t,i)=>{ const r=rows[i]; if(!r) return; const T=t.getBoundingClientRect(), Rr=r.querySelector('i').getBoundingClientRect(); const x1=T.right-D.left, y1=T.top+T.height/2-D.top, x2=Rr.left-D.left, y2=Rr.top+Rr.height/2-D.top; const mx=x1+(x2-x1)*.55; const d='M'+x1+' '+y1+' H'+(mx-40)+' C'+(mx-10)+' '+y1+' '+(mx-10)+' '+y2+' '+(mx+20)+' '+y2+' H'+x2; const soon=t.classList.contains('soon');
      out+='<path class="base'+(soon?' soon':'')+'" d="'+d+'"/>'+(soon?'':'<path class="pulse" style="animation-delay:'+(-i*.7)+'s" d="'+d+'"/>')+'<circle class="dot" cx="'+x1+'" cy="'+y1+'" r="2.5" opacity="'+(soon?.3:1)+'"/>'; });
    sv.innerHTML=out; };
  draw(); addEventListener('resize',draw); setTimeout(draw,600); setTimeout(draw,1600);
  // globe: dotted wireframe sphere, slow spin, only while on screen
  const gc=document.getElementById('igGlobe'); if(gc){ const g=gc.getContext('2d'); let on=false; new IntersectionObserver(es=>es.forEach(e=>on=e.isIntersecting)).observe(gc);
    const pts=[]; for(let i=0;i<1400;i++){ const y=1-(i/1399)*2, r=Math.sqrt(1-y*y), th=i*2.39996; pts.push([r*Math.cos(th),y,r*Math.sin(th)]); }
    const rings=[]; for(let k=0;k<6;k++){ const lat=-60+k*24; const ry=Math.sin(lat*Math.PI/180), rr=Math.cos(lat*Math.PI/180); for(let a=0;a<360;a+=2){ rings.push([rr*Math.cos(a*Math.PI/180),ry,rr*Math.sin(a*Math.PI/180)]); } } for(let k=0;k<8;k++){ const lon=k*22.5*Math.PI/180; for(let a=0;a<360;a+=2){ const t=a*Math.PI/180; rings.push([Math.cos(t)*Math.cos(lon),Math.sin(t),Math.cos(t)*Math.sin(lon)]); } }
    const loop=()=>{ if(on){ const s=gc.clientWidth; if(gc.width!==s*devicePixelRatio){ gc.width=gc.height=s*devicePixelRatio; } const W=gc.width, c=W/2, R=W*.42, t=performance.now()/1000; g.clearRect(0,0,W,W);
      const proj=(x,y,z)=>{ const a=t*.12; const X=x*Math.cos(a)-z*Math.sin(a), Z=x*Math.sin(a)+z*Math.cos(a); const tilt=.35; const Y=y*Math.cos(tilt)-Z*Math.sin(tilt), Z2=y*Math.sin(tilt)+Z*Math.cos(tilt); const p=1/(2.6-Z2); return [c+X*R*p*1.6, c+Y*R*p*1.6, Math.max(0,(Z2+1)/2)]; };
      for(const [x,y,z] of rings){ const [px,py,d]=proj(x,y,z); g.fillStyle='rgba(120,160,255,'+(d*.42+.05).toFixed(3)+')'; g.fillRect(px,py,1*devicePixelRatio,1*devicePixelRatio); }
      for(const [x,y,z] of pts){ const [px,py,d]=proj(x,y,z); const s=(1.4+d*1.6)*devicePixelRatio; g.fillStyle='rgba(120,160,255,'+(d*.95+.08).toFixed(3)+')'; g.fillRect(px-s/2,py-s/2,s,s); }
      // soft blue body so the sphere reads as a volume
      const grd=g.createRadialGradient(c,c,R*.1,c,c,R*1.05); grd.addColorStop(0,'rgba(30,91,255,.22)'); grd.addColorStop(.7,'rgba(30,91,255,.10)'); grd.addColorStop(1,'rgba(30,91,255,0)'); g.fillStyle=grd; g.beginPath(); g.arc(c,c,R*1.05,0,Math.PI*2); g.fill(); }
      requestAnimationFrame(loop); }; loop(); }
})();
