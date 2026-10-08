"""Generates the Issue 1 parcel-state mockups (untracked unit rows, RTO forward leg) in the
signed-off design/returns-parcel-states visual language, EN/LTR and AR/RTL."""
import os

OUT = '/Users/marawan/Documents/traceability/design/returns-parcel-states'
MONO = "'IBM Plex Mono', ui-monospace, monospace"

ICON_BOX = '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 8l-9-5-9 5 9 5 9-5z"></path><path d="M3 8v8l9 5 9-5V8"></path><path d="M12 13v8"></path></svg>'
ICON_CHECK = '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12l5 5L20 7"></path></svg>'
ICON_SHIRT = '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9 3l-5 2.5L2 10l3 1.5V21h14v-9.5l3-1.5-2-4.5L15 3a3 3 0 0 1-6 0z"></path></svg>'
ICON_INFO = '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"></circle><path d="M12 8h.01"></path><path d="M11 12h1v5h1"></path></svg>'
ICON_PHONE = '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="6" y="2" width="12" height="20" rx="2"></rect><path d="M11 18h2"></path></svg>'
ICON_UNDO = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9 14L4 9l5-5"></path><path d="M4 9h11a5 5 0 0 1 0 10h-3"></path></svg>'
ICON_SCAN = '<svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 8V6a2 2 0 0 1 2-2h2M16 4h2a2 2 0 0 1 2 2v2M20 16v2a2 2 0 0 1-2 2h-2M8 20H6a2 2 0 0 1-2-2v-2M7 12h10"></path></svg>'
ICON_X = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"></path></svg>'
ICON_TICK = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12l5 5L20 7"></path></svg>'

PILL = {
    'success': ('#E6F4EC', '#1B6B43'),
    'warning': ('#FDEBD8', '#9A4A08'),
    'neutral': ('#E9ECF2', '#3A4458'),
    'info':    ('#E4EAFD', '#2440B8'),
    'critical': ('#FCEBEA', '#B12D25'),
}

T = {
 'en': dict(
    dir='ltr', lang='en', font="-apple-system, BlinkMacSystemFont, 'Segoe UI', 'Helvetica Neue', sans-serif",
    fonts='family=IBM+Plex+Mono:wght@400;500;600',
    leave='Leave session view', session='Return session', started='started 6:55 PM', abandon='Abandon session',
    scanLabel='Scan a piece or an AWB', scanPh='Scan a piece or an AWB…', hid='auto-focused · HID ready',
    recognised='Parcel label recognised · ', courier='Courier return', rts='Return to sender',
    order='Order', customer='Customer', back='Back at warehouse', bostaNote="Bosta's note", bostaStatus='Bosta status',
    itemsOnOrder='Items on this order',
    untrackedHint='No Traced labels on these — mark each item that came back. Unmarked items didn\'t come back.',
    scanHint="Scan each item's Traced label. Label missing? Reprint it first.",
    allDecided='Everything from this parcel is decided.',
    notTracked='Not tracked', unit='Unit {i} of {n}', viaPhone='via phone',
    arrivedSellable='Arrived · sellable', arrivedDamagedAction='Arrived · damaged',
    toReceive='Arrived · to receive', damaged='Arrived · damaged', undo='Undo',
    scanned='Scanned', restock='Restock', damagedBtn='Damaged',
    progress='{s} of {n} in', allIn='All {n} in', allInShort='All items in', progressNone='{s} of {n} in',
    boxTitle="Traced didn't track this order",
    boxBody="It was shipped before its items were tracked in Traced, so there are no labels to scan. Open the parcel and mark each item that came back — sellable or damaged. Items you don't mark are treated as not returned (the customer may have kept them).",
    boxWarn="Sellable items go on managers' Return To Receive list for their next Receiving session — Traced won't add them to stock from here. Don't restock them in Shopify, or they'll be counted twice. Damaged items are only recorded.",
    boxBodyMixed="Some items on this order were shipped before Traced tracked them. Scan the labelled ones as usual; mark the others that came back. Unmarked items are treated as not returned.",
    mismatch="Contents don't match the order?", markReceived='Mark parcel received',
    mismatchHint='Records the whole parcel as received instead — managers add what came back in Receiving.',
    rtsTitle='Returned to sender — never delivered',
    rtsBody="Bosta brought this parcel back to you, so it should hold everything on the order. Traced didn't track the order, so there are no labels to scan — mark each item that came back.",
    footerOne='1 parcel', footerTwo='2 parcels', leftOne='1 item left to mark', nothingLeft='nothing left to decide', handled='handled · ready to close', notYet='not handled yet',
    close='Close session', customerName='Omar K.', customer2='Nour H.', date='20 Sep', date2='8 Oct',
    rtoState='Returned to business', rtoSince='returned to origin since 5 Oct',
    collapsedRestocked='Order #1052 · 1 item · restocked',
 ),
 'ar': dict(
    dir='rtl', lang='ar', font="'IBM Plex Sans Arabic', -apple-system, 'Segoe UI', sans-serif",
    fonts='family=IBM+Plex+Mono:wght@400;500;600&amp;family=IBM+Plex+Sans+Arabic:wght@400;500;600;700',
    leave='مغادرة الجلسة', session='جلسة المرتجعات', started='بدأت 6:55 م', abandon='إلغاء الجلسة',
    scanLabel='امسح قطعة أو بوليصة شحن', scanPh='امسح قطعة أو بوليصة شحن…', hid='تركيز تلقائي · الماسح جاهز',
    recognised='تم التعرّف على ملصق الشحنة · ', courier='مرتجع من بوستا', rts='مرتجع للمرسل',
    order='الطلب', customer='العميل', back='وصل المستودع', bostaNote='ملاحظة بوستا', bostaStatus='حالة بوستا',
    itemsOnOrder='قطع هذا الطلب',
    untrackedHint='لا توجد ملصقات Traced على هذه القطع — سجّل كل قطعة عادت. القطع غير المسجّلة لم تَعُد.',
    scanHint='امسح ملصق Traced لكل قطعة. الملصق مفقود؟ أعد طباعته أولًا.',
    allDecided='تم اتخاذ القرار بشأن كل قطع هذه الشحنة.',
    notTracked='غير متتبَّع', unit='القطعة {i} من {n}', viaPhone='عبر الهاتف',
    arrivedSellable='وصل · صالح للبيع', arrivedDamagedAction='وصل · تالف',
    toReceive='وصل · بانتظار الاستلام', damaged='وصل · تالف', undo='تراجع',
    scanned='تم المسح', restock='إعادة للمخزون', damagedBtn='تالف',
    progress='وصلت {s} من {n}', allIn='وصلت كلها ({n})', allInShort='وصلت كل القطع', progressNone='وصلت {s} من {n}',
    boxTitle='لم يتتبّع Traced هذا الطلب',
    boxBody='شُحن هذا الطلب قبل تتبّع قطعه في Traced، لذلك لا توجد ملصقات لمسحها. افتح الشحنة وسجّل كل قطعة عادت — صالحة للبيع أو تالفة. القطع التي لا تسجّلها تُعتبر لم تَعُد (ربما احتفظ بها العميل).',
    boxWarn='القطع الصالحة للبيع تُضاف إلى قائمة «مرتجع بانتظار الاستلام» لدى المديرين لجلسة الاستلام القادمة — لن يضيفها Traced إلى المخزون من هنا. لا تُعِدها إلى المخزون في Shopify حتى لا تُحتسب مرتين. القطع التالفة تُسجَّل فقط.',
    boxBodyMixed='شُحنت بعض قطع هذا الطلب قبل أن يتتبّعها Traced. امسح القطع التي عليها ملصق كالمعتاد، وسجّل ما عاد من الباقي. القطع غير المسجّلة تُعتبر لم تَعُد.',
    mismatch='المحتوى لا يطابق الطلب؟', markReceived='تسجيل الشحنة كمستلمة',
    mismatchHint='يسجّل الشحنة كلها كمستلمة بدلًا من ذلك — ويضيف المديرون ما عاد في جلسة الاستلام.',
    rtsTitle='مرتجع للمرسل — لم يُسلَّم',
    rtsBody='أعادت بوستا هذه الشحنة إليك، لذلك يُفترض أن تحتوي على كل قطع الطلب. لم يتتبّع Traced هذا الطلب، فلا توجد ملصقات لمسحها — سجّل كل قطعة عادت.',
    footerOne='شحنة واحدة', footerTwo='شحنتان', leftOne='قطعة واحدة بانتظار التسجيل', nothingLeft='لا شيء بانتظار قرار', handled='تم التعامل معها · جاهزة للإغلاق', notYet='لم يتم التعامل معها بعد',
    close='إغلاق الجلسة', customerName='عمر ك.', customer2='نور ح.', date='20 سبتمبر', date2='8 أكتوبر',
    rtoState='Returned to business', rtoSince='مرتجع للمرسل منذ 5 أكتوبر',
    collapsedRestocked='الطلب ‎#1052‎ · قطعة واحدة · أُعيدت للمخزون',
 ),
}


def pill(tone, label):
    bg, fg = PILL[tone]
    return f'<div style="padding: 6px 12px; border-radius: 999px; background: {bg}; color: {fg}; font-size: 13px; font-weight: 600; white-space: nowrap;">{label}</div>'


def small_pill(tone, label):
    bg, fg = PILL[tone]
    return f'<div style="padding: 5px 10px; border-radius: 999px; background: {bg}; color: {fg}; font-size: 13px; font-weight: 600; white-space: nowrap;">{label}</div>'


def btn(label, kind='outline', icon=''):
    if kind == 'brand':
        style = 'border: none; background: #3656E0; color: #FFFFFF;'
    elif kind == 'danger':
        style = 'border: 1px solid #EBC3BF; background: #FFFFFF; color: #B12D25;'
    else:
        style = 'border: 1px solid #CDD3DC; background: #FFFFFF; color: #141821;'
    return (f'<button style="min-height: 44px; padding: 0 16px; display: flex; align-items: center; gap: 8px; '
            f'border-radius: 10px; {style} font-size: 14px; font-weight: 600; font-family: inherit; white-space: nowrap;">{icon}{label}</button>')


def mono(text):
    return f'<bdi style="font-family: {MONO};">{text}</bdi>'


def fact(label, value):
    return (f'<div style="display: flex; flex-direction: column; gap: 2px;"><div style="font-size: 12px; color: #5B6270;">{label}</div>'
            f'<div style="font-size: 15px; font-weight: 600; color: #141821;">{value}</div></div>')


def card_head(t, kicker, awb, order, customer, date, pill_html, complete=False):
    icon_bg, icon_fg, icon = ('#E6F4EC', '#1B6B43', ICON_CHECK) if complete else ('#EEF2FF', '#3656E0', ICON_BOX)
    return f'''<div style="padding: 20px 24px; display: flex; align-items: center; gap: 20px;">
<div style="width: 44px; height: 44px; flex-shrink: 0; border-radius: 10px; background: {icon_bg}; color: {icon_fg}; display: flex; align-items: center; justify-content: center;">{icon}</div>
<div style="display: flex; flex-direction: column; gap: 2px; width: 230px;">
<div style="font-size: 12px; font-weight: 600; letter-spacing: 0.06em; text-transform: uppercase; color: #5B6270;">{kicker}</div>
<div style="font-family: {MONO}; font-size: 18px; font-weight: 600; color: #141821;"><bdi>AWB {awb}</bdi></div>
</div>
<div style="display: flex; gap: 36px; flex-grow: 1;">
{fact(t['order'], f'<bdi>{order}</bdi>')}
{fact(t['customer'], customer)}
{fact(t['back'], date)}
</div>
{pill_html}
</div>'''


def strip(label, body_html):
    return f'''<div style="padding: 12px 24px; background: #F8F9FB; border-top: 1px solid #EDEFF3; display: flex; gap: 16px; align-items: baseline;">
<div style="font-size: 12px; font-weight: 600; letter-spacing: 0.06em; text-transform: uppercase; color: #5B6270; white-space: nowrap; width: 110px; flex-shrink: 0;">{label}</div>
<div style="font-size: 14px; color: #2A303B; line-height: 1.5;">{body_html}</div>
</div>'''


def bosta_note(t, count, desc):
    if t['lang'] == 'ar':
        lead = 'قطعة واحدة — ' if count == 1 else f'{count} قطع — '
    else:
        lead = f'{count} item — ' if count == 1 else f'{count} items — '
    return strip(t['bostaNote'], f'{lead}<bdi dir="ltr">{desc}</bdi>')


def items_head(t, hint):
    return f'''<div style="padding: 14px 24px; border-top: 1px solid #EDEFF3; display: flex; align-items: center; gap: 16px;">
<div style="font-size: 14px; font-weight: 600; color: #141821;">{t['itemsOnOrder']}</div>
<div style="flex-grow: 1;"></div>
<div style="font-size: 13px; color: #5B6270;">{hint}</div>
</div>'''


def via_phone(t):
    return (f'<span style="display: inline-flex; align-items: center; gap: 4px; padding: 2px 8px; border-radius: 999px; '
            f'background: #EEF0F3; color: #3A4458; font-size: 12px; font-weight: 600;">{ICON_PHONE}{t["viaPhone"]}</span>')


def unit_row(t, product, variant, i, n, state, phone=False):
    """state: awaiting | sellable | damaged"""
    tag = small_pill('neutral', t['notTracked'])
    meta = f'<div style="font-size: 13px; color: #5B6270;">{variant} · {t["unit"].format(i=i, n=n)}</div>'
    if phone:
        meta = f'<div style="display: flex; align-items: center; gap: 8px; font-size: 13px; color: #5B6270;">{variant} · {t["unit"].format(i=i, n=n)} {via_phone(t)}</div>'
    if state == 'awaiting':
        tail = btn(t['arrivedSellable'], 'brand') + btn(t['arrivedDamagedAction'], 'danger')
        status = ''
    else:
        status = small_pill('info' if state == 'sellable' else 'critical', t['toReceive'] if state == 'sellable' else t['damaged'])
        tail = btn(t['undo'], 'outline', ICON_UNDO)
    return f'''<div style="padding: 14px 24px; border-top: 1px solid #EDEFF3; display: flex; align-items: center; gap: 16px;">
<div style="width: 48px; height: 48px; flex-shrink: 0; border-radius: 10px; background: #EEF0F3; color: #6B7280; display: flex; align-items: center; justify-content: center;">{ICON_SHIRT}</div>
<div style="display: flex; flex-direction: column; gap: 2px; width: 300px;"><div style="font-size: 15px; font-weight: 600; color: #141821;"><bdi>{product}</bdi></div>{meta}</div>
<div style="width: 110px;">{tag}</div>
{status}
<div style="flex-grow: 1;"></div>
<div style="display: flex; gap: 10px;">{tail}</div>
</div>'''


def tracked_row(t, product, variant, code):
    return f'''<div style="padding: 14px 24px; border-top: 1px solid #EDEFF3; display: flex; align-items: center; gap: 16px;">
<div style="width: 48px; height: 48px; flex-shrink: 0; border-radius: 10px; background: #EEF0F3; color: #6B7280; display: flex; align-items: center; justify-content: center;">{ICON_SHIRT}</div>
<div style="display: flex; flex-direction: column; gap: 2px; width: 300px;"><div style="font-size: 15px; font-weight: 600; color: #141821;"><bdi>{product}</bdi></div><div style="font-size: 13px; color: #5B6270;">{variant}</div></div>
<div style="font-family: {MONO}; font-size: 14px; color: #2A303B; width: 110px;"><bdi>{code}</bdi></div>
{small_pill('success', t['scanned'])}
<div style="flex-grow: 1;"></div>
<div style="display: flex; gap: 10px;">{btn(t['restock'])}{btn(t['damagedBtn'], 'danger')}</div>
</div>'''


def info_box(title, body, warn):
    return f'''<div style="padding: 20px 24px; border-top: 1px solid #EDEFF3;">
<div style="display: flex; gap: 16px; padding: 18px 20px; border-radius: 12px; background: #FBF3EA; border: 1px solid #F0D5B6;">
<span style="color: #A8560C; display: flex; flex-shrink: 0;">{ICON_INFO}</span>
<div style="display: flex; flex-direction: column; gap: 6px;">
<div style="font-size: 16px; font-weight: 600; color: #8A4608;">{title}</div>
<div style="font-size: 14px; color: #2A303B; line-height: 1.55;">{body}</div>
<div style="font-size: 14px; color: #2A303B; line-height: 1.55; font-weight: 600;">{warn}</div>
</div>
</div>
</div>'''


def mismatch_row(t):
    return f'''<div style="padding: 0 24px 20px; display: flex; align-items: center; gap: 12px;">
<div style="font-size: 14px; color: #2A303B; font-weight: 600;">{t['mismatch']}</div>
{btn(t['markReceived'])}
<div style="font-size: 13px; color: #5B6270;">{t['mismatchHint']}</div>
</div>'''


def collapsed(t, awb, summary, label):
    return f'''<section style="background: #FFFFFF; border: 1px solid #E3E6EB; border-radius: 14px; padding: 16px 24px; display: flex; align-items: center; gap: 20px;">
<div style="width: 44px; height: 44px; flex-shrink: 0; border-radius: 10px; background: #E6F4EC; color: #1B6B43; display: flex; align-items: center; justify-content: center;">{ICON_CHECK}</div>
<div style="font-family: {MONO}; font-size: 16px; font-weight: 600; color: #141821; width: 230px;"><bdi>AWB {awb}</bdi></div>
<div style="font-size: 14px; color: #5B6270; flex-grow: 1;">{summary}</div>
{pill('success', label)}
</section>'''


def page(t, title, awb, cards, footer_left, height=1000):
    return f'''<!doctype html>
<html lang="{t['lang']}"{' dir="rtl"' if t['dir'] == 'rtl' else ''}>
<head>
<meta charset="utf-8">
<title>{title}</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
<link href="https://fonts.googleapis.com/css2?{t['fonts']}&amp;display=swap" rel="stylesheet">
<style>
body{{margin:0;background:#F4F5F7}}
a{{color:#3656E0}}a:hover{{color:#2440B8}}
</style>
</helmet>
<div dir="{t['dir']}" lang="{t['lang']}" style="width: 1280px; height: {height}px; box-sizing: border-box; display: flex; flex-direction: column; background: #F4F5F7; font-family: {t['font']}; color: #141821;">

<header style="height: 64px; box-sizing: border-box; padding: 0 32px; display: flex; align-items: center; gap: 16px; background: #FFFFFF; border-bottom: 1px solid #E3E6EB;">
<button aria-label="{t['leave']}" style="width: 44px; height: 44px; display: flex; align-items: center; justify-content: center; border: none; background: transparent; color: #3A404C; border-radius: 10px; padding: 0;">{ICON_X}</button>
<div style="font-size: 18px; font-weight: 600; color: #141821;">{t['session']} {mono('RT-3BB8')}</div>
<div style="font-size: 14px; color: #5B6270;">{t['started']}</div>
<div style="flex-grow: 1;"></div>
<button style="border: none; background: transparent; color: #B12D25; font-size: 15px; font-weight: 600; padding: 0 12px; min-height: 44px; font-family: inherit;">{t['abandon']}</button>
</header>

<div style="padding: 20px 32px; display: flex; align-items: center; gap: 16px; border-bottom: 1px solid #E3E6EB;">
<span style="color: #3656E0; display: flex;">{ICON_SCAN}</span>
<label for="scan" style="position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0);">{t['scanLabel']}</label>
<input id="scan" type="text" placeholder="{t['scanPh']}" style="flex-grow: 1; height: 64px; box-sizing: border-box; padding: 0 20px; border: 2px solid #9FB2F2; border-radius: 12px; box-shadow: 0 0 0 4px #E4EAFD; font-family: {MONO}; font-size: 22px; color: #141821; background: #FFFFFF; outline: none;">
<div style="font-size: 13px; color: #5B6270; white-space: nowrap;">{t['hid']}</div>
</div>

<div style="padding: 14px 32px 0; display: flex;">
<div style="display: flex; align-items: center; gap: 8px; padding: 8px 14px; border-radius: 999px; background: #E6F4EC; color: #1B6B43; font-size: 14px; font-weight: 500;">{ICON_TICK}<span>{t['recognised']}{mono('AWB ' + awb)}</span></div>
</div>

<main style="flex-grow: 1; padding: 16px 32px 20px; display: flex; flex-direction: column; gap: 16px; overflow: hidden;">
{cards}
</main>

<footer style="padding: 14px 32px 24px; background: #FFFFFF; border-top: 1px solid #E3E6EB; display: flex; flex-direction: column; gap: 10px;">
<div style="font-size: 13px; color: #5B6270; text-align: center;">{footer_left}</div>
<button style="height: 52px; border-radius: 10px; border: none; background: #3656E0; color: #FFFFFF; font-size: 16px; font-weight: 600; font-family: inherit;">{t['close']}</button>
</footer>

</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{{"$preview":{{"width":1280,"height":{height}}}}}'>
class Component extends DCLogic {{
  renderVals() {{
    return {{}};
  }}
}}
</script>
</body>
</html>
'''


def state3(t):
    """Courier return, untracked order, no request: one row per unit, mixed outcomes, one via phone."""
    head = card_head(t, t['courier'], '6136538746', '#0988', t['customerName'], t['date'],
                     pill('neutral', t['progress'].format(s=2, n=3)), complete=True)
    body = (bosta_note(t, 3, 'Flipped Pants in Black - XL x 2 (1001-Black-XL), Linen Shirt in White - M x 1 (2004-White-M)')
            + items_head(t, t['untrackedHint'])
            + unit_row(t, 'Flipped Pants', 'Black · XL', 1, 2, 'sellable', phone=True)
            + unit_row(t, 'Flipped Pants', 'Black · XL', 2, 2, 'damaged')
            + unit_row(t, 'Linen Shirt', 'White · M', 1, 1, 'awaiting')
            + info_box(t['boxTitle'], t['boxBody'], t['boxWarn']))
    card = f'<section style="background: #FFFFFF; border: 1px solid #E3E6EB; border-radius: 14px; overflow: hidden;">\n{head}\n{body}\n</section>'
    return page(t, 'Return session — order not tracked (unit rows)' if t['lang'] == 'en' else 'جلسة المرتجعات — طلب غير متتبَّع (قطعة بقطعة)',
                '6136538746', card, f"{t['footerOne']} · {t['handled']}", height=1060)


def state3b(t):
    """Tracked pieces AND untracked lines on the same order."""
    head = card_head(t, t['courier'], '8012985727', '#1061', t['customer2'], t['date'],
                     pill('warning', t['progress'].format(s=1, n=3)))
    body = (bosta_note(t, 3, 'Linen Shirt in White - M x 1 (2004-White-M), Wide Trousers in Sand - L x 2 (3010-Sand-L)')
            + items_head(t, t['scanHint'])
            + tracked_row(t, 'Linen Shirt', 'White · M', 'P000245')
            + unit_row(t, 'Wide Trousers', 'Sand · L', 1, 2, 'sellable')
            + unit_row(t, 'Wide Trousers', 'Sand · L', 2, 2, 'awaiting')
            + info_box(t['boxTitle'], t['boxBodyMixed'], t['boxWarn']))
    card = f'<section style="background: #FFFFFF; border: 1px solid #E3E6EB; border-radius: 14px; overflow: hidden;">\n{head}\n{body}\n</section>'
    return page(t, 'Return session — tracked and untracked items on one order', '8012985727', card,
                f"{t['footerOne']} · {t['notYet']}", height=1060)


def state3c(t):
    """All units arrived — parcel complete (Undo still offered while the session is open)."""
    head = card_head(t, t['courier'], '6136538746', '#0988', t['customerName'], t['date'],
                     pill('success', t['allIn'].format(n=3)), complete=True)
    body = (bosta_note(t, 3, 'Flipped Pants in Black - XL x 2 (1001-Black-XL), Linen Shirt in White - M x 1 (2004-White-M)')
            + items_head(t, t['allDecided'])
            + unit_row(t, 'Flipped Pants', 'Black · XL', 1, 2, 'sellable', phone=True)
            + unit_row(t, 'Flipped Pants', 'Black · XL', 2, 2, 'damaged')
            + unit_row(t, 'Linen Shirt', 'White · M', 1, 1, 'sellable'))
    card = f'<section style="background: #FFFFFF; border: 1px solid #E3E6EB; border-radius: 14px; overflow: hidden;">\n{head}\n{body}\n</section>'
    cards = card + '\n' + collapsed(t, '2493716277', t['collapsedRestocked'], t['allInShort'])
    return page(t, 'Return session — untracked parcel, all items arrived', '6136538746', cards,
                f"{t['footerTwo']} · {t['nothingLeft']}", height=1000)


def state8(t):
    """Forward leg Bosta returned to origin (RTO), untracked order: Bosta status + note, unit rows,
    Mark parcel received offered as the mismatch fallback while nothing is marked yet."""
    head = card_head(t, t['rts'], '445040939', '#2212099474', t['customer2'], t['date2'],
                     pill('warning', t['progress'].format(s=0, n=1)))
    status = strip(t['bostaStatus'], f'<bdi dir="ltr">{t["rtoState"]}</bdi> · {t["rtoSince"]}')
    body = (status
            + bosta_note(t, 1, 'The Bikinis - XS/S / Pink &amp; Red x 1 (SBPINK-2)')
            + items_head(t, t['untrackedHint'])
            + unit_row(t, 'The Bikinis', 'XS/S · Pink &amp; Red', 1, 1, 'awaiting')
            + info_box(t['rtsTitle'], t['rtsBody'], t['boxWarn'])
            + mismatch_row(t))
    card = f'<section style="background: #FFFFFF; border: 1px solid #E3E6EB; border-radius: 14px; overflow: hidden;">\n{head}\n{body}\n</section>'
    return page(t, 'Return session — returned to sender, order not tracked' if t['lang'] == 'en' else 'جلسة المرتجعات — مرتجع للمرسل، طلب غير متتبَّع',
                '445040939', card, f"{t['footerOne']} · {t['notYet']}", height=1000)


files = {
    '3-order-not-tracked.dc.html': state3(T['en']),
    '6-order-not-tracked-ar.dc.html': state3(T['ar']),
    '3b-tracked-and-untracked.dc.html': state3b(T['en']),
    '3c-untracked-all-arrived.dc.html': state3c(T['en']),
    '3c-untracked-all-arrived-ar.dc.html': state3c(T['ar']),
    '8-returned-to-sender.dc.html': state8(T['en']),
    '8-returned-to-sender-ar.dc.html': state8(T['ar']),
}
for name, html in files.items():
    with open(os.path.join(OUT, name), 'w') as f:
        f.write(html)
    print('wrote', name, len(html))
