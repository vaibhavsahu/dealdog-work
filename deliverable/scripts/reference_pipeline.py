#!/usr/bin/env python3
"""
Reference pipeline — a faithful structural mirror of the Java implementation.

Purpose: this harness exists so the resolution ALGORITHM can be executed and validated
against validation/public_validation_cases.json in an environment without a Maven
repository. It mirrors com.dealdog.* class-for-class (Norm, Policies, Adapters, Catalog,
Resolver, Ingestor, Exporter) so any behavioural fix found here ports mechanically.

It is a development/verification tool, not the submission runtime.
"""
from __future__ import annotations
import csv, json, re, sys, hashlib
from pathlib import Path
from collections import OrderedDict

ROOT = Path(__file__).resolve().parent.parent
DATA = next(p for p in (ROOT.parent/"provided"/"data", ROOT/"data", ROOT.parent/"data") if p.is_dir())

# ---------------------------------------------------------------- Norm
HTML_TAG = re.compile(r"<[^>]{1,80}>")
COLORS = {"black","white","silver","midnight","starlight","blue","ocean blue","ultramarine",
          "red","natural","gold","gray","grey","green","pink","blk"}
COLOR_ALIAS = {"blk":"black"}
STOP = {"inch","gb","tb","ml","oz","mm","us","ram","pack","size","months","month","fl",
        "the","with","for","and","pcs","gen","usb","type","series","qled","edp","edt","no",
        "unlocked","phone","black","white","silver","midnight","plus","includes","gift","card",
        "payments","save","only","total","free","up","to","x","nvme","pcie","m","lot","pay","members",
        "one","two","three","four","five","six","seven","eight","nine","ten","series","set","case"}

# A number followed by a unit is a measurement, not a model number ("Arc Series Two 41 mm").
UNIT_AFTER = re.compile(r'\s?(mm|gb|tb|ml|oz|in\b|inch|"|percent|%|pack|x\b|fl)', re.I)
CODE = re.compile(r"\b([A-Za-z]{1,8}-?\d{1,4}[A-Za-z0-9]*(?:-[A-Za-z0-9]{1,6})*)\b")
WORDNUM = re.compile(r"\b([A-Za-z]{3,15})[\s-](\d{1,4})\b")

def clean(s):
    if s is None: return None
    t = HTML_TAG.sub(" ", s)
    t = (t.replace(" "," ").replace("​","").replace("‎","").replace("‏","")
          .replace("‑","-").replace("–","-").replace("—","-"))
    t = "".join(chr(ord(c)-0xFEE0) if 0xFF01 <= ord(c) <= 0xFF5E else c for c in t)
    return re.sub(r"\s+"," ",t).strip()

def parse_money(raw):
    if raw is None: return None
    t = clean(str(raw)).replace("$","").replace("USD","").strip()
    m = re.match(r"^(-?\d{1,3}(?:,\d{3})*(?:\.\d+)?|-?\d+(?:\.\d+)?)$", t)
    if not m: return None
    try: return float(m.group(1).replace(",",""))
    except Exception: return None

def gtin_canonical(raw):
    if raw is None: return None
    d = str(raw).strip()
    if not re.fullmatch(r"\d{8}|\d{11,14}", d): return None
    return "0"*(14-len(d)) + d

def gtin_checksum_ok(raw):
    p = gtin_canonical(raw)
    if p is None: return False
    s = sum(int(p[i])*(3 if i%2==0 else 1) for i in range(13))
    return (10 - s%10)%10 == int(p[13])

def gtin_validity(raw):
    if raw is None or str(raw).strip()=="" : return "missing"
    if gtin_canonical(raw) is None: return "malformed"
    return "valid" if gtin_checksum_ok(raw) else "checksum_invalid"

def squeeze(s):
    if s is None: return None
    t = re.sub(r"[^A-Za-z0-9]","", clean(str(s))).upper()
    return t or None

GENERIC_HEAD = {"solid","state","drive","internal","wireless","premium","professional","cordless",
 "countertop","stick","road","running","waterproof","everyday","quantum","noise","canceling",
 "last","previous","weekend","special","new","with","the","and","for","plus","best","top"}

# Tokens that look like model codes but are interface/form-factor specs, not identity.
SPEC_TOKENS = {"M2","M22280","PCIE4","PCIE3","PCIE5","USB2","USB3","USBC","WIFI5","WIFI6","WIFI7",
               "TYPE1","TYPE2","GEN1","GEN2","GEN3","DDR4","DDR5"}

def family(token):
    # Single-letter heads are legitimate model codes (P16, V12), so keep {1,8}; spec-shaped
    # tokens are filtered by name instead.
    m = re.match(r"^([A-Za-z]{1,8})-?(\d{1,4})", token)
    if not m: return None
    fam = (m.group(1)+m.group(2)).upper()
    return None if fam in SPEC_TOKENS else fam

def fallback_tokens(title, brand):
    """
    Product families whose model code carries no digits (e.g. "Quanta NVX Pro") would otherwise
    produce no blocking token at all. Fall back to brand-level tokens so such listings can still
    be blocked together; the variant stage still requires full price-critical agreement.
    """
    out = []
    if brand:
        s = squeeze(brand)
        if s and len(s) >= 3: out.append(s)
    for w in re.findall(r"[A-Za-z][A-Za-z\-]{2,}", clean(title) or ""):
        if w.lower() in GENERIC_HEAD or w.lower() in STOP: continue
        s = squeeze(w)
        if s and len(s) >= 3 and s not in out: out.append(s)
        break
    return out

def family_tokens(text):
    out = []
    if not text: return out
    t = clean(text)
    for m in CODE.finditer(t):
        tok = m.group(1)
        head = re.sub(r"[-\d].*","",tok).lower()
        if head in STOP: continue
        fam = family(tok)
        if fam and len(fam) >= 2 and fam.lower() not in STOP and fam not in out: out.append(fam)
    for m in WORDNUM.finditer(t):
        w = m.group(1).lower()
        if w in STOP: continue
        if UNIT_AFTER.match(t[m.end():]): continue
        f = (m.group(1)+m.group(2)).upper()
        if f not in out: out.append(f)
    return out

def full_codes(text):
    out = []
    if not text: return out
    for m in CODE.finditer(clean(text)):
        tok = m.group(1)
        head = re.sub(r"[-\d].*","",tok).lower()
        if head in STOP or not re.search(r"\d", tok): continue
        sq = squeeze(tok)
        if sq and len(sq) >= 3 and sq not in out: out.append(sq)
    return out

def from_title(title, src):
    out = []
    if not title: return out
    t = clean(title); low = t.lower()
    color = None
    for c in COLORS:
        if re.search(r"\b"+re.escape(c)+r"\b", low):
            if color is None or len(c) > len(color): color = c
    if "black/white" in low or "black white" in low: color = "black/white"
    if color: out.append(("color", COLOR_ALIAS.get(color,color), src, color, "normalized"))

    best, best_raw = -1, None
    for m in re.finditer(r"\b(\d{2,4})\s?gb\b(?!\s?ram)", t, re.I):
        v = float(m.group(1))
        if v > best: best, best_raw = v, m.group()
    if best > 0:
        nominal = 1000.0 if best == 1024 else 2000.0 if best == 2048 else best
        out.append(("storage_gb", nominal, src, best_raw, "normalized"))
    m = re.search(r"\b(\d{1,2})\s?tb\b", t, re.I)
    if m: out.append(("storage_gb", float(m.group(1))*1000, src, m.group(), "normalized"))

    m = re.search(r"\b(\d{1,4})\s?m[lL]\b", t)
    if m: out.append(("volume_ml", float(m.group(1)), src, m.group(), "normalized"))
    else:
        m = re.search(r"\b(\d(?:\.\d)?)\s?fl\s?oz\b", t, re.I)
        if m:
            v = float(m.group(1))*29.5735
            nominal = 30.0 if abs(v-30)<2 else 50.0 if abs(v-50)<2 else 100.0 if abs(v-100)<3 else round(v)
            out.append(("volume_ml", nominal, src, m.group(), "inferred"))

    m = re.search(r"\bus\s?(\d{1,2}(?:\.5)?)\b", t, re.I)
    if m: out.append(("size_us", m.group(1), src, m.group(), "normalized"))
    if re.search(r"\bmedium\b", low): out.append(("size","M",src,"medium","normalized"))
    elif re.search(r"\blarge\b", low) and "x-large" not in low: out.append(("size","L",src,"large","normalized"))
    elif re.search(r"\bsmall\b", low): out.append(("size","S",src,"small","normalized"))

    if re.search(r"\b(men's|mens)\b", low): out.append(("department","mens",src,"men's","normalized"))
    elif re.search(r"\b(women's|womens)\b", low): out.append(("department","womens",src,"women's","normalized"))

    m = re.search(r"\b(\d{2}(?:\.\d)?)[\s-]?(?:inch|in\b|\")", t, re.I)
    if m: out.append(("screen_in", float(m.group(1)), src, m.group(), "normalized"))
    m2 = re.search(r"\b(1[0-7](?:\.\d)?)[\s-]?(?:inch|in\b)", t, re.I)
    if m2: out.append(("screen_in", float(m2.group(1)), src, m2.group(), "normalized"))

    m = re.search(r"\b(\d{2})\s?mm\b", t, re.I)
    if m: out.append(("case_size_mm", float(m.group(1)), src, m.group(), "normalized"))
    m = re.search(r"\b(\d{1,2})\s?gb\s?ram\b", t, re.I)
    if m: out.append(("ram_gb", float(m.group(1)), src, m.group(), "normalized"))

    m = re.search(r"\b(\d)\s?[-\s]?pack\b|\b(\d)\s?x\b", t, re.I)
    if m:
        n = m.group(1) or m.group(2)
        out.append(("bundle", f"{n}_pack", src, m.group(), "normalized"))
    if "single pair" in low: out.append(("bundle","single_pair",src,"single pair","normalized"))
    elif "standard tools" in low: out.append(("bundle","standard_tools",src,"standard tools","normalized"))
    elif "watch only" in low: out.append(("bundle","watch_only",src,"watch only","normalized"))
    elif "drive only" in low: out.append(("bundle","drive_only",src,"drive only","normalized"))
    elif "standard pitcher" in low: out.append(("bundle","standard_pitcher",src,"standard pitcher","normalized"))
    elif "gift set" in low or "set with travel" in low: out.append(("bundle","gift_set",src,"gift set","normalized"))

    if "digital" in low: out.append(("edition","digital",src,"digital","normalized"))
    elif "disc" in low or "optical drive" in low: out.append(("edition","disc",src,"disc/optical","normalized"))

    m = re.search(r"\b(20\d{2})\b", t)
    if m: out.append(("generation", float(m.group(1)), src, m.group(), "normalized"))

    if "eau de parfum" in low or re.search(r"\bedp\b", low): out.append(("formulation","eau_de_parfum",src,"eau de parfum","normalized"))
    elif "eau de toilette" in low or re.search(r"\bedt\b", low): out.append(("formulation","eau_de_toilette",src,"eau de toilette","normalized"))
    return out

def canon_value(key, v):
    if v is None: return None
    if key == "color" and isinstance(v, str):
        s = v.strip().lower()
        return COLOR_ALIAS.get(s, s)
    if isinstance(v, str): return v.strip().lower().replace(" ","_")
    return v

def canon(v):
    if isinstance(v, float) and v == int(v): v = int(v)
    return str(v).strip().lower()

def sku_from_url(url):
    if not url: return None
    p = url.split("?")[0].rstrip("/")
    return p.split("/")[-1] or None

CANONICAL = {"brand","model","generation","storage_gb","color","carrier","screen_in","ram_gb","gpu",
 "processor_generation","case_size_mm","connectivity","case_material","bundle","capacity_gb",
 "interface","form_factor","heatsink","sensor_format","included_lens","warranty_region",
 "edition","department","size_us","size","width","line","formulation_family","volume_ml",
 "formulation","strength_pct","style_code","season","width_mm","aspect_ratio","rim_in",
 "load_index","speed_rating","extra_load","run_flat","quantity","voltage_platform","tool_type",
 "voltage_v","battery_count","battery_capacity_ah","charger_included","compatible_printer_family",
 "yield_pages","yield_class","oem_status","cartridge_count","work","language","format","region",
 "disc_count","license","wifi_generation","band_count","max_speed_mbps","ethernet_ports","poe",
 "node_count"}

# ---------------------------------------------------------------- Policies
class Policies:
    def __init__(self, doc):
        self.categories = {}
        cfg = set()
        for name, c in doc["categories"].items():
            self.categories[name] = {
                "product": c["product_dimensions"],
                "variant": c["variant_dimensions"],
                "price_critical": c["price_critical_dimensions"]}
            cfg.update(c["variant_dimensions"])
        cfg.update(["color","size","department","bundle","case_connector"])
        self.config_keys = sorted(cfg)
        self.default = {"product":["brand","model","generation"],
                        "variant":self.config_keys, "price_critical":self.config_keys}

    def for_category(self, cat): return self.categories.get(cat, self.default)

    def infer(self, attrs, title, brand):
        k = set(attrs); t = (title or "").lower()
        if "capacity_gb" in k or ("interface" in k and "form_factor" in k): return "storage_devices"
        if "case_size_mm" in k or "case_material" in k or "smartwatch" in t: return "smartwatches"
        if "formulation" in k or "volume_ml" in k: return "beauty_and_fragrance"
        if "size_us" in k or "running shoe" in t or "road shoe" in t: return "footwear"
        if "edition" in k and ("console" in t or "novaplay" in t): return "game_consoles"
        if "ram_gb" in k or "laptop" in t or "airbook" in t or "air book" in t: return "laptops"
        if ("carrier" in k or "phone" in t) and "headphone" not in t: return "phones"
        if any(w in t for w in ("headphone","earbud","airbud","buds")): return "headphones"
        if "console" in t or "novaplay" in t: return "game_consoles"
        if "television" in t or " tv" in t or "qled" in t: return "tvs"
        if "vacuum" in t: return "vacuums"
        if "blender" in t: return "small_appliances"
        if any(w in t for w in ("tee","jacket","crew","shirt")): return "apparel"
        if any(w in t for w in ("serum","parfum","fragrance","toilette")): return "beauty_and_fragrance"
        if any(w in t for w in ("ssd","solid state","nvme","internal drive")): return "storage_devices"
        if "watch" in t: return "smartwatches"
        return "unknown"

# ---------------------------------------------------------------- Adapters
def _scope(scopes, ns, dflt): return (scopes or {}).get(ns, dflt)

class X:  # RawExtraction
    def __init__(self):
        self.record_id=None; self.seller=None; self.sku=None; self.title=None; self.brand=None
        self.condition="new"; self.availability=None; self.product_type="primary_product"
        self.content_origin=None; self.observed_at=None; self.source_updated_at=None; self.url=None
        self.attrs=[]; self.ids=[]; self.money=None

def _add_gtin(x, raw, scopes, field):
    if not raw: return
    x.ids.append({"ns":"gtin","raw":raw,"canonical":gtin_canonical(raw),
                  "scope":_scope(scopes,"gtin","exact_variant"),"validity":gtin_validity(raw),"field":field})
def _add_mpn(x, raw, scopes, field):
    if not raw: return
    bad = "?" in str(raw)
    x.ids.append({"ns":"mpn","raw":raw,"canonical":None if bad else squeeze(raw),
                  "scope":_scope(scopes,"mpn","exact_variant"),"validity":"malformed" if bad else "valid","field":field})
def _add_sku(x, raw, scopes, field):
    if not raw: return
    x.sku = raw
    x.ids.append({"ns":"merchant_sku","raw":raw,"canonical":squeeze(raw),
                  "scope":_scope(scopes,"merchant_sku","merchant_offer"),"validity":"valid","field":field})

def _attrs_from(x, obj, prefix):
    for k,v in (obj or {}).items():
        if v is None: continue
        if k == "condition": x.condition = v; continue
        if k == "style_code" and isinstance(v,str):
            x.ids.append({"ns":"style_code","raw":v,"canonical":squeeze(v),"scope":"style_colorway","validity":"valid","field":f"{prefix}.{k}"})
        x.attrs.append((k, v, f"{prefix}.{k}", str(v), "explicit"))

def _money(amount, list_price, currency, kind, comp, terms, avail, observed, field, raw):
    return {"amount":amount,"list":list_price,"currency":currency or "USD",
            "price_kind":kind or "total_purchase_price","comparability":comp,
            "terms":dict(terms) if terms else {}, "availability":avail,
            "observed_at":observed, "field":field, "raw":raw,
            "validity":"invalid" if (amount is None and raw not in (None,"")) else "valid"}

def ad_affiliate_a(p):
    x=X(); x.record_id=p.get("record_id"); x.seller=p.get("merchant")
    x.title=clean(p.get("product_name")); x.condition=p.get("condition") or "new"
    x.availability=p.get("availability"); x.product_type=p.get("product_type") or "primary_product"
    x.content_origin=p.get("upstream_origin") or None
    x.source_updated_at=p.get("last_updated"); x.observed_at=x.source_updated_at; x.url=p.get("deep_link")
    _add_sku(x,p.get("merchant_sku"),None,"merchant_sku"); _add_gtin(x,p.get("ean"),None,"ean")
    _add_mpn(x,p.get("manufacturer_part_number"),None,"manufacturer_part_number")
    x.attrs += from_title(x.title,"product_name")
    terms={}
    promo=p.get("promotion_text")
    if promo:
        if promo.strip().startswith("{"):
            try: terms=json.loads(promo)
            except Exception: terms={"promotion_text":promo}
        else: terms={"promotion_text":promo}
    x.money=_money(parse_money(p.get("sale_price")), parse_money(p.get("retail_price")),
                   p.get("currency"), p.get("price_kind"), p.get("comparability"),
                   terms, x.availability, x.observed_at, "sale_price", p.get("sale_price"))
    return x

def ad_affiliate_b(p):
    x=X(); sem=p.get("semantics") or {}; scopes=sem.get("identifierScopes")
    x.record_id=p.get("eventId"); x.seller=(p.get("advertiser") or {}).get("name")
    item=p.get("item") or {}
    x.title=clean(item.get("title")); x.product_type=sem.get("productType") or "primary_product"
    x.content_origin=sem.get("contentOrigin") or p.get("contentLineage")
    x.observed_at=(p.get("stock") or {}).get("capturedAt"); x.source_updated_at=x.observed_at
    x.url=p.get("trackingUrl"); x.availability=(p.get("stock") or {}).get("status")
    _add_sku(x,item.get("merchantItemId"),scopes,"item.merchantItemId")
    ids=item.get("identifiers") or {}
    _add_gtin(x,ids.get("globalTradeItemNumber"),scopes,"item.identifiers.globalTradeItemNumber")
    _add_mpn(x,ids.get("manufacturerCode"),scopes,"item.identifiers.manufacturerCode")
    _attrs_from(x,item.get("variant"),"item.variant")
    x.attrs += from_title(x.title,"item.title")
    pr=p.get("pricing") or {}
    x.money=_money(pr.get("current"), pr.get("original"), pr.get("currencyCode"),
                   sem.get("priceKind"), sem.get("comparabilityHint"), pr.get("promotion"),
                   x.availability, x.observed_at, "pricing.current", pr.get("current"))
    return x

def ad_retailer_v1(p):
    x=X(); sem=p.get("semantics") or {}; scopes=sem.get("identifier_scopes")
    x.record_id=p.get("observation_id"); x.seller=(p.get("store") or {}).get("display_name")
    prod=p.get("product") or {}
    x.title=clean(prod.get("name")); x.brand=prod.get("brand")
    x.product_type=sem.get("product_type") or "primary_product"; x.content_origin=sem.get("content_origin")
    x.observed_at=p.get("observed_at"); x.source_updated_at=x.observed_at; x.url=p.get("product_url")
    offer=p.get("offer") or {}
    x.availability=offer.get("availabilityCode")
    _add_sku(x,p.get("sku"),scopes,"sku"); _add_gtin(x,prod.get("barcode"),scopes,"product.barcode")
    _add_mpn(x,prod.get("model"),scopes,"product.model")
    _attrs_from(x,prod.get("specifications"),"product.specifications")
    x.attrs += from_title(x.title,"product.name")
    price=(offer.get("price") or {})
    x.money=_money(price.get("amount"), offer.get("listPrice"), price.get("currency"),
                   sem.get("price_kind"), sem.get("comparability_hint"), offer.get("terms"),
                   x.availability, x.observed_at, "offer.price.amount", price.get("amount"))
    return x

def ad_retailer_compact(p):
    x=X(); sem=p.get("semantics") or {}
    x.record_id=p.get("observation_id"); x.seller=(p.get("store") or {}).get("display_name")
    prod=p.get("product") or {}
    x.title=clean(prod.get("displayName") or prod.get("name"))
    ident=prod.get("identity") or {}
    x.brand=ident.get("brand")
    x.product_type=sem.get("productType") or "primary_product"; x.content_origin=sem.get("contentOrigin")
    x.observed_at=p.get("observed_at"); x.source_updated_at=x.observed_at; x.url=p.get("product_url")
    offer=p.get("offer") or {}
    x.availability=offer.get("availability")
    _add_sku(x,p.get("sku"),None,"sku"); _add_mpn(x,ident.get("model"),None,"product.identity.model")
    for k,v in (ident.get("codes") or {}).items():
        if k.lower()=="gtin": _add_gtin(x,v,None,"product.identity.codes.gtin")
    for kv in prod.get("configuration") or []:
        k,v = kv.get("key"), kv.get("value")
        if k is None or v is None: continue
        if k=="condition": x.condition=v; continue
        x.attrs.append((k,v,f"product.configuration[{k}]",str(v),"explicit"))
    x.attrs += from_title(x.title,"product.displayName")
    money=(offer.get("money") or {})
    amount = money.get("minorAmount")/100.0 if money.get("minorAmount") is not None else None
    lst = offer.get("compareAtMinorAmount")/100.0 if offer.get("compareAtMinorAmount") is not None else None
    x.money=_money(amount, lst, money.get("currency"), sem.get("priceKind"), None,
                   offer.get("context"), x.availability, x.observed_at,
                   "offer.money.minorAmount", money.get("minorAmount"))
    return x

def ad_community(p):
    x=X(); x.record_id=p.get("report_id"); x.seller=p.get("merchant")
    x.title=clean(p.get("title")); x.product_type=p.get("product_type") or "primary_product"
    x.content_origin=p.get("content_origin")
    x.observed_at=p.get("posted_at"); x.source_updated_at=x.observed_at; x.url=p.get("url")
    x.availability=p.get("availability_claim")
    _add_sku(x,p.get("merchant_hint_sku"),None,"merchant_hint_sku")
    pid=p.get("partial_identifiers") or {}
    _add_gtin(x,pid.get("gtin"),None,"partial_identifiers.gtin")
    _add_mpn(x,pid.get("mpn"),None,"partial_identifiers.mpn")
    x.attrs += from_title(x.title,"title")
    if p.get("user_confidence") is not None:
        x.attrs.append(("user_confidence",p["user_confidence"],"user_confidence",str(p["user_confidence"]),"explicit"))
    terms=dict(p.get("offer_context") or {})
    req=p.get("requirements")
    if req:
        if req.strip().startswith("{"):
            try: terms["requirements"]=json.loads(req)
            except Exception: terms["requirements"]=req
        else: terms["requirements"]=req
    if p.get("coupon_code"): terms["coupon_code"]=p["coupon_code"]
    x.money=_money(p.get("reported_price"), p.get("usual_price"), "USD", p.get("price_kind"),
                   p.get("comparability_hint"), terms, x.availability, x.observed_at,
                   "reported_price", p.get("reported_price"))
    return x

def ad_extension(p):
    x=X(); hints=p.get("semantic_hints") or {}; scopes=hints.get("identifierScopes")
    x.record_id=p.get("capture_id"); x.seller=p.get("merchant")
    dom=p.get("dom") or {}
    x.title=clean(dom.get("title")); x.product_type=hints.get("productType") or "primary_product"
    x.content_origin=p.get("content_origin")
    x.observed_at=p.get("observed_at"); x.source_updated_at=x.observed_at; x.url=p.get("page_url")
    x.availability=dom.get("stockText")
    _add_sku(x,sku_from_url(x.url),scopes,"page_url")
    pid=p.get("partial_identifiers") or {}
    _add_gtin(x,pid.get("gtin"),scopes,"partial_identifiers.gtin")
    _add_mpn(x,pid.get("mpn"),scopes,"partial_identifiers.mpn")
    _attrs_from(x,dom.get("selectedOptions"),"dom.selectedOptions")
    x.attrs += from_title(x.title,"dom.title")
    x.money=_money(parse_money(dom.get("priceText")), None, "USD", dom.get("priceKind"),
                   hints.get("comparability"), p.get("offer_context"), x.availability,
                   x.observed_at, "dom.priceText", dom.get("priceText"))
    return x

def select_adapter(source, p):
    if source=="retailer_api":
        if p.get("schema_version")=="2026-08-compact" or isinstance((p.get("product") or {}).get("configuration"), list):
            return ("retailer_api:2026-08-compact", ad_retailer_compact)
        if "observation_id" in p and (p.get("product") or {}).get("specifications") is not None:
            return ("retailer_api:v1", ad_retailer_v1)
        return None
    if source=="affiliate_a" and "record_id" in p and "product_name" in p: return ("affiliate_a:csv_v1", ad_affiliate_a)
    if source=="affiliate_b" and "eventId" in p and "item" in p: return ("affiliate_b:feed_v3.7", ad_affiliate_b)
    if source=="community_deals" and "report_id" in p: return ("community_deals:v1", ad_community)
    if source=="extension_observations" and "capture_id" in p: return ("extension_observations:0.9.x", ad_extension)
    return None

# ---------------------------------------------------------------- Catalog
def sha1(s): return hashlib.sha1(s.encode()).hexdigest()[:16]

class Listing:
    def __init__(self):
        self.internal_id=None; self.source=None; self.record_id=None; self.epoch=1
        self.seller=None; self.sku=None; self.lifecycle="active"; self.adapter=None
        self.raw=None; self.category="unknown"; self.brand=None; self.title=None
        self.content_origin=None; self.observed_at=None; self.source_updated_at=None
        self.product_type="primary_product"; self.condition="new"
        self.fields=OrderedDict(); self.unknown=OrderedDict(); self.provenance=[]
        self.ids=[]; self.money=None; self.family=[]; self.code_family=[]; self.codes=[]; self.history=[]
        self.conflicts=set(); self.quarantined=False; self.quarantine_reason=None
        self.product_id=None; self.variant_id=None; self.decision=None; self.confidence=0.5
        self.pos=[]; self.neg=[]; self.hyp=[]; self.cand_sources={}; self.scored=set(); self.cand_count=0
    def asserted(self,k):
        f=self.fields.get(k)
        return f["value"] if f and f["state"]=="asserted" else None
    def asserted_attrs(self):
        return {k:f["value"] for k,f in self.fields.items() if f["state"]=="asserted"}
    def valid_ids(self, ns):
        return [i for i in self.ids if i["ns"]==ns and i["canonical"] and i["validity"]!="malformed"]
    def has_bad_checksum(self):
        return any(i["validity"]=="checksum_invalid" for i in self.ids)

class Catalog:
    def __init__(self, policies):
        self.policies=policies
        self.listings=OrderedDict(); self.record_index={}
        self.products=OrderedDict(); self.variants=OrderedDict(); self.offers=OrderedDict()
        self.audits=[]; self.quarantine=[]
        self.parent={}
    def find(self,t):
        p=self.parent.get(t)
        if p is None: self.parent[t]=t; return t
        if p==t: return t
        r=self.find(p); self.parent[t]=r; return r
    def union(self,a,b):
        ra,rb=self.find(a),self.find(b)
        if ra==rb: return
        root,child=(ra,rb) if ra<rb else (rb,ra)
        self.parent[child]=root
    def union_all(self,tokens):
        first=None
        for t in tokens:
            if first is None: first=t; self.find(t)
            else: self.union(first,t)
    def roots(self,tokens): return {self.find(t) for t in tokens}
    def register(self,l):
        self.listings[l.internal_id]=l
        self.record_index[(l.source,l.record_id)]=l.internal_id
    def by_record(self,source,rid):
        iid=self.record_index.get((source,rid))
        return self.listings.get(iid) if iid else None
    def get_or_create_product(self, category, brand, root, generation):
        key=f"{category or 'unknown'}|{(brand or '').lower()}|{(root or '').lower()}|{canon(generation) if generation is not None else ''}"
        pid="up:"+sha1(key)
        p=self.products.get(pid)
        if not p:
            p={"id":pid,"category":category or "unknown","brand":brand,"attrs":OrderedDict(),
               "family":set(),"listings":set(),"variants":set()}
            if brand: p["attrs"]["brand"]=brand
            if root: p["attrs"]["model"]=root; p["family"].add(root)
            if generation is not None: p["attrs"]["generation"]=generation
            self.products[pid]=p
        return p
    def get_or_create_variant(self, p, dims):
        items=sorted(dims.items())
        key=p["id"]+"".join(f"|{k}={canon(v)}" for k,v in items)
        vid="var:"+sha1(key)
        v=self.variants.get(vid)
        if not v:
            v={"id":vid,"product_id":p["id"],"attrs":OrderedDict(items),"listings":set()}
            self.variants[vid]=v; p["variants"].add(vid)
        return v
    def variant_gtins(self,v,exclude):
        out=set()
        for lid in v["listings"]:
            if lid==exclude: continue
            l=self.listings.get(lid)
            if l:
                for i in l.valid_ids("gtin"): out.add(i["canonical"])
        return out
    def variant_mpns(self,v,exclude):
        out=set()
        for lid in v["listings"]:
            if lid==exclude: continue
            l=self.listings.get(lid)
            if l:
                for i in l.valid_ids("mpn"):
                    if i["scope"]=="exact_variant": out.add(i["canonical"])
        return out
    def product_tokens(self,p,exclude=None):
        # `exclude` keeps a re-resolving listing from matching its own product through the very
        # tokens it just changed - without it, a correction could never move a listing.
        out=set()
        for lid in p["listings"]:
            if lid==exclude: continue
            l=self.listings.get(lid)
            if l: out |= self.roots(l.family)
        if not out and exclude is None: out=set(self.roots(p["family"]))
        return out
    def product_code_tokens(self,p,exclude=None):
        """Model-family roots derived from real codes only (brand fallback tokens excluded)."""
        out=set()
        for lid in p["listings"]:
            if lid==exclude: continue
            l=self.listings.get(lid)
            if l and l.code_family: out |= self.roots(l.code_family)
        return out
    def product_codes(self,p,exclude=None):
        out=set()
        for lid in p["listings"]:
            if lid==exclude: continue
            l=self.listings.get(lid)
            if not l: continue
            for i in l.ids:
                if i["canonical"] and i["ns"] in ("mpn","style_code"): out.add(i["canonical"])
            out |= set(l.codes)
        return out
    def detach(self,l):
        if l.variant_id and l.variant_id in self.variants:
            self.variants[l.variant_id]["listings"].discard(l.internal_id)
        if l.product_id and l.product_id in self.products:
            self.products[l.product_id]["listings"].discard(l.internal_id)
        l.variant_id=None; l.product_id=None
    def attach(self,l,p,v):
        if p:
            l.product_id=p["id"]; p["listings"].add(l.internal_id)
            if l.category!="unknown" and p["category"]=="unknown": p["category"]=l.category
            p["family"] |= self.roots(l.family)
        if v:
            l.variant_id=v["id"]; v["listings"].add(l.internal_id)
    def prune(self):
        for vid in [v["id"] for v in self.variants.values() if not v["listings"]]:
            v=self.variants.pop(vid); self.products[v["product_id"]]["variants"].discard(vid)
        for pid in [p["id"] for p in self.products.values() if not p["listings"] and not p["variants"]]:
            self.products.pop(pid)
        for o in self.offers.values():
            if o["variant_id"] and o["variant_id"] not in self.variants: o["variant_id"]=None
            if o["product_id"] and o["product_id"] not in self.products: o["product_id"]=None

# ---------------------------------------------------------------- Resolver
def signal(field,src,raw,note,effect):
    s={"canonical_field":field,"note":note,"effect":effect}
    if src: s["source_field"]=src
    if raw is not None: s["raw_value"]=str(raw)
    return s

class Resolver:
    def __init__(self,cat): self.cat=cat; self.pol=cat.policies

    def price_critical_dims(self,l):
        cp=self.pol.for_category(l.category)
        keys=list(dict.fromkeys(list(cp["variant"])+list(cp["price_critical"])))
        out=OrderedDict()
        for k in keys:
            v=l.asserted(k)
            if v is not None: out[k]=v
        return out

    def generate(self,l):
        sources={}; strength={}; pins={}; id_backed=set()
        def add(idx,eid): sources.setdefault(idx,set()).add(eid)
        my_g={i["canonical"] for i in l.valid_ids("gtin")}
        my_m_exact={i["canonical"] for i in l.ids if i["canonical"] and i["ns"] in ("mpn","style_code") and i["scope"]=="exact_variant"}
        my_m_broad={i["canonical"] for i in l.ids if i["canonical"] and i["ns"] in ("mpn","style_code") and i["scope"]!="exact_variant"}
        for v in self.cat.variants.values():
            vg=self.cat.variant_gtins(v,l.internal_id); vm=self.cat.variant_mpns(v,l.internal_id)
            g_hit=bool(my_g & vg); m_hit=bool(my_m_exact & vm)
            if g_hit: add("gtin_index",v["id"])
            if m_hit: add("mpn_index",v["id"])
            if g_hit or m_hit:
                strength[v["product_id"]]=max(strength.get(v["product_id"],0),3)
                pins.setdefault(v["product_id"],set()).add(v["id"])
                id_backed.add(v["product_id"])
        if l.seller and l.sku:
            # A merchant SKU declared as a CONFIGURABLE offer addresses a parent page carrying
            # many configurations; it is product-level evidence only and must never pin a variant.
            configurable = any(i["ns"]=="merchant_sku" and i["scope"]=="configurable_offer" for i in l.ids)
            key=(l.seller.strip().lower(), l.sku)
            for o in self.cat.listings.values():
                if o is l or not o.seller or not o.sku: continue
                if (o.seller.strip().lower(), o.sku)!=key: continue
                o_conf = any(i["ns"]=="merchant_sku" and i["scope"]=="configurable_offer" for i in o.ids)
                if o.variant_id and not configurable and not o_conf:
                    add("merchant_sku_index",o.variant_id)
                    strength[o.product_id]=max(strength.get(o.product_id,0),3)
                    pins.setdefault(o.product_id,set()).add(o.variant_id)
                elif o.product_id:
                    add("merchant_sku_index",o.product_id)
                    strength[o.product_id]=max(strength.get(o.product_id,0),2)
        my_codes=set(l.codes)|my_m_exact|my_m_broad
        my_roots=self.cat.roots(l.family)
        for p in self.cat.products.values():
            if my_codes & self.cat.product_codes(p,l.internal_id):
                add("full_code_index",p["id"]); strength[p["id"]]=max(strength.get(p["id"],0),2)
            if my_roots & self.cat.product_tokens(p,l.internal_id):
                add("token_index",p["id"]); strength[p["id"]]=max(strength.get(p["id"],0),1)
        return sources,strength,pins,id_backed

    def resolve(self,l,commit,event_key):
        r={"decision":"REVIEW","product":None,"variant":None,"confidence":0.5,
           "pos":[],"neg":[],"hyp":[],"sources":{},"scored":set()}
        if l.quarantined:
            r["confidence"]=0.1
            r["neg"].append(signal("record",None,l.quarantine_reason,"quarantined record: no adapter could safely interpret payload","negative"))
            self._finish(l,r,commit,event_key); return r
        sources,strength,pins,id_backed=self.generate(l)
        r["sources"]=sources
        union=set()
        for s in sources.values(): union|=s
        for eid in sorted(union):
            pid = self.cat.variants[eid]["product_id"] if eid.startswith("var:") and eid in self.cat.variants else eid
            p=self.cat.products.get(pid)
            if not p: continue
            if self._cat_ok(l.category,p["category"]): r["scored"].add(eid)
            else: r["neg"].append(signal("category",None,l.category,f"candidate {eid} dropped: category {p['category']} incompatible","negative"))

        best=[]; best_s=0
        for pid,s in sorted(strength.items()):
            p=self.cat.products.get(pid)
            if not p or not self._cat_ok(l.category,p["category"]): continue
            lg=l.asserted("generation"); pg=p["attrs"].get("generation")
            if lg is not None and pg is not None and canon(lg)!=canon(pg):
                r["neg"].append(signal("generation",self._fsrc(l,"generation"),lg,f"candidate product {pid} dropped: generation conflict","negative")); continue
            if s>best_s: best_s=s; best=[pid]
            elif s==best_s: best.append(pid)

        product=None
        if len(best)==1:
            product=self.cat.products[best[0]]
            # Identifier evidence pointing at a product whose model family is entirely disjoint
            # from this listing's own model codes is a copied/mis-scoped identifier, not proof of
            # identity (e.g. a P15 listing carrying P16's GTIN). Refuse to merge; abstain instead.
            my_code_roots=self.cat.roots(l.code_family) if l.code_family else set()
            p_roots=self.cat.product_code_tokens(product,l.internal_id)
            if best[0] in id_backed and my_code_roots and p_roots and not (my_code_roots & p_roots):
                r["neg"].append(signal("identifier",self._idsrc(l),self._firstid(l),
                    "identifier matches a product whose model family contradicts this listing's own model code","negative"))
                r["hyp"].append({"universal_product_id":product["id"],"variant_id":None,"score":0.5})
                self._finish(l,r,commit,event_key); return r
            # Other independently-supported candidates that are compatible describe the same
            # marketed model reached by different evidence: unify them rather than leaving
            # duplicate clusters behind.
            if commit:
                others=[pid for pid,s in strength.items()
                        if pid!=best[0] and s>=2 and pid in self.cat.products
                        and self._cat_ok(l.category,self.cat.products[pid]["category"])]
                if others and self._mergeable([best[0]]+others):
                    product=self._merge([best[0]]+others,event_key)
        elif len(best)>1:
            if best_s>=2 and commit and self._mergeable(best): product=self._merge(best,event_key)
            elif best_s>=2:
                for pid in best: r["hyp"].append({"universal_product_id":pid,"variant_id":None,"score":0.5})
                r["neg"].append(signal("identity",None,None,"exact-scope evidence points at incompatible products","negative"))
                self._finish(l,r,commit,event_key); return r
            else:
                refined=[pid for pid in best if set(l.codes) & self.cat.product_codes(self.cat.products[pid])]
                if len(refined)==1: product=self.cat.products[refined[0]]
                else:
                    for pid in best: r["hyp"].append({"universal_product_id":pid,"variant_id":None,"score":0.4})
                    self._finish(l,r,commit,event_key); return r

        primary = l.product_type=="primary_product"
        if product is None:
            has_ev = bool(l.family) or bool(l.valid_ids("gtin")) or bool(l.valid_ids("mpn")) or bool(self.price_critical_dims(l))
            if commit and primary and has_ev:
                product=self.cat.get_or_create_product(l.category,l.brand,self._root(l),l.asserted("generation"))
                r["pos"].append(signal("model",self._fsrc(l,"model"),self._root(l),"new universal product established from evidence","positive"))
            else:
                r["decision"]="NO_MATCH" if not union else "REVIEW"
                r["confidence"]=0.2 if not union else 0.4
                for eid in sorted(r["scored"]):
                    if eid.startswith("up:"): r["hyp"].append({"universal_product_id":eid,"variant_id":None,"score":0.3})
                self._finish(l,r,commit,event_key); return r
        elif not primary:
            r["hyp"].append({"universal_product_id":product["id"],"variant_id":None,"score":0.4})
            r["neg"].append(signal("product_type","product_type",l.product_type,"source declares non-primary product type; related-product match suppressed","negative"))
            self._finish(l,r,commit,event_key); return r
        else:
            r["pos"].append(signal("model",self._fsrc(l,"model"),",".join(sorted(self.cat.roots(l.family))),f"product-level evidence agrees with {product['id']}","positive"))
        r["product"]=product["id"]
        if commit: self._upgrade(product,l)

        dims=self.price_critical_dims(l)
        conflicted=sorted(set(l.conflicts) & set(dims))
        pin_set={vid for vid in pins.get(product["id"],set()) if vid in self.cat.variants}

        if l.has_bad_checksum():
            r["neg"].append(signal("gtin",self._idsrc(l),self._firstid(l),"GTIN check digit does not validate; identifier used as weaker evidence","negative"))

        if conflicted:
            for k in conflicted:
                r["neg"].append(signal(k,self._fsrc(l,k),dims.get(k),f"conflicting evidence for price-critical dimension {k}","negative"))
            for v in self._pvars(product):
                if self._agrees_shared(v,dims,set(conflicted)):
                    r["hyp"].append({"universal_product_id":product["id"],"variant_id":v["id"],"score":0.5})
            self._finish(l,r,commit,event_key); return r

        if len(pin_set)==1:
            v=self.cat.variants[list(pin_set)[0]]
            if self._conflicts(v,dims):
                # An identifier can legitimately name a broader thing than the offer: a contained
                # unit inside a retailer-added bundle, a pack, or a copied code. Rather than let a
                # contradicted pin veto good structured evidence, fall back to attribute-based
                # resolution WITHIN the same product. Ambiguity there still yields REVIEW.
                r["neg"].append(signal("identifier",self._idsrc(l),self._firstid(l),
                    f"exact-scope identifier pins {v['id']} but explicit attributes conflict; identifier scope treated as broader than the offer","negative"))
                viable=[c for c in self._pvars(product) if self._viable(c,dims)]
                if len(viable)==1:
                    return self._match(l,r,product,viable[0],dims,commit,False,event_key)
                if not viable and commit and dims:
                    nv=self.cat.get_or_create_variant(product,dims)
                    return self._match(l,r,product,nv,dims,commit,False,event_key)
                r["hyp"].append({"universal_product_id":product["id"],"variant_id":v["id"],"score":0.5})
                for c in viable: r["hyp"].append({"universal_product_id":product["id"],"variant_id":c["id"],"score":0.5})
                self._finish(l,r,commit,event_key); return r
            return self._match(l,r,product,v,dims,commit,True,event_key)
        if len(pin_set)>1:
            compat=[self.cat.variants[vid] for vid in sorted(pin_set) if not self._conflicts(self.cat.variants[vid],dims)]
            if len(compat)==1: return self._match(l,r,product,compat[0],dims,commit,True,event_key)
            for vid in sorted(pin_set): r["hyp"].append({"universal_product_id":product["id"],"variant_id":vid,"score":0.5})
            r["neg"].append(signal("identifier",self._idsrc(l),self._firstid(l),"identifier is shared by multiple variants; scope insufficient for exact-variant proof","negative"))
            self._finish(l,r,commit,event_key); return r

        viable=[v for v in self._pvars(product) if self._viable(v,dims)]
        if len(viable)==1: return self._match(l,r,product,viable[0],dims,commit,False,event_key)
        if len(viable)>1:
            for v in viable: r["hyp"].append({"universal_product_id":product["id"],"variant_id":v["id"],"score":round(1.0/len(viable),2)})
            r["neg"].append(signal("variant",None,None,f"listing lacks dimensions that distinguish {len(viable)} viable variants","negative"))
            self._finish(l,r,commit,event_key); return r
        if commit and (dims or not self._pvars(product)):
            v=self.cat.get_or_create_variant(product,dims)
            return self._match(l,r,product,v,dims,commit,False,event_key)
        for v in self._pvars(product): r["hyp"].append({"universal_product_id":product["id"],"variant_id":v["id"],"score":0.4})
        self._finish(l,r,commit,event_key); return r

    def _match(self,l,r,p,v,dims,commit,pinned,event_key):
        r["decision"]="MATCH"; r["variant"]=v["id"]; r["confidence"]=0.95 if pinned else 0.9
        if pinned:
            r["pos"].append(signal("identifier",self._idsrc(l),self._firstid(l),"valid exact-scope identifier equals variant identifier set","positive"))
        for k,val in dims.items():
            if k in v["attrs"] and canon(v["attrs"][k])==canon(val):
                r["pos"].append(signal(k,self._fsrc(l,k),val,"price-critical dimension agrees with variant","positive"))
        if not r["pos"]:
            r["pos"].append(signal("model",self._fsrc(l,"model"),",".join(sorted(self.cat.roots(l.family))),"single compatible variant of matched product","positive"))
        if commit:
            for k,val in dims.items():
                if k not in v["attrs"]: v["attrs"][k]=val
        self._finish(l,r,commit,event_key); return r

    def _finish(self,l,r,commit,event_key):
        if not commit: return
        prior_p,prior_v=l.product_id,l.variant_id
        self.cat.detach(l)
        p=self.cat.products.get(r["product"]) if r["product"] else None
        v=self.cat.variants.get(r["variant"]) if r["variant"] else None
        self.cat.attach(l,p,v)
        l.decision=r["decision"]; l.confidence=r["confidence"]
        l.pos=r["pos"]; l.neg=r["neg"]; l.hyp=r["hyp"]
        l.cand_sources={k:sorted(v2) for k,v2 in r["sources"].items()}
        allc=set()
        for s in r["sources"].values(): allc|=s
        l.cand_count=len(allc); l.scored=set(r["scored"])
        if (prior_p,prior_v)!=(l.product_id,l.variant_id) and (prior_p or prior_v):
            self.cat.audits.append({"listing_internal_id":l.internal_id,"event_key":event_key,
                "prior_universal_product_id":prior_p,"prior_variant_id":prior_v,
                "new_universal_product_id":l.product_id,"new_variant_id":l.variant_id,
                "reason":"re-resolution under new evidence",
                "authority":"authoritative_correction" if event_key and "correct" in str(event_key) else "evidence"})

    def _pvars(self,p): return [self.cat.variants[v] for v in sorted(p["variants"]) if v in self.cat.variants]
    def _viable(self,v,dims):
        for k,val in dims.items():
            if k not in v["attrs"]: return False
            if canon(v["attrs"][k])!=canon(val): return False
        return True
    def _conflicts(self,v,dims):
        return any(k in v["attrs"] and canon(v["attrs"][k])!=canon(val) for k,val in dims.items())
    def _agrees_shared(self,v,dims,ignore):
        return all(not(k in v["attrs"] and canon(v["attrs"][k])!=canon(val)) for k,val in dims.items() if k not in ignore)
    def _find_dims(self,p,dims):
        for v in self._pvars(p):
            if self._viable(v,dims): return v
        return None
    @staticmethod
    def _cat_ok(a,b): return a is None or b is None or a=="unknown" or b=="unknown" or a==b
    def _mergeable(self,pids):
        for i in range(len(pids)):
            for j in range(i+1,len(pids)):
                a,b=self.cat.products.get(pids[i]),self.cat.products.get(pids[j])
                if not a or not b: return False
                if not self._cat_ok(a["category"],b["category"]): return False
                ga,gb=a["attrs"].get("generation"),b["attrs"].get("generation")
                if ga is not None and gb is not None and canon(ga)!=canon(gb): return False
        return True
    def _merge(self,pids,event_key):
        s=sorted(pids); target=self.cat.products[s[0]]
        for pid in s[1:]:
            src=self.cat.products.get(pid)
            if not src or src is target: continue
            for vid in sorted(src["variants"]):
                sv=self.cat.variants.get(vid)
                if not sv: continue
                tv=self.cat.get_or_create_variant(target,sv["attrs"])
                for lid in sorted(sv["listings"]):
                    m=self.cat.listings.get(lid)
                    if not m: continue
                    self.cat.audits.append({"listing_internal_id":lid,"event_key":event_key,
                        "prior_universal_product_id":src["id"],"prior_variant_id":sv["id"],
                        "new_universal_product_id":target["id"],"new_variant_id":tv["id"],
                        "reason":"product merge: exact-scope evidence links product clusters","authority":"evidence"})
                    self.cat.detach(m); self.cat.attach(m,target,tv)
                sv["listings"]=set()
            target["family"] |= src["family"]; src["variants"]=set(); src["listings"]=set()
        self.cat.prune()
        return target
    def _upgrade(self,p,l):
        if not p["brand"] and l.brand: p["brand"]=l.brand; p["attrs"]["brand"]=l.brand
        g=l.asserted("generation")
        if g is not None and "generation" not in p["attrs"]: p["attrs"]["generation"]=g
        p["family"] |= self.cat.roots(l.family)
    def _root(self,l):
        for i in l.ids:
            if i["ns"] in ("mpn","style_code") and i["canonical"]:
                f=family(str(i["raw"]))
                if f: return self.cat.find(f)
        for t in l.family: return self.cat.find(t)
        return None
    def _fsrc(self,l,k):
        f=l.fields.get(k); return f["source_field"] if f else None
    def _idsrc(self,l):
        for i in l.ids:
            if i["canonical"]: return i["field"]
        return None
    def _firstid(self,l):
        for i in l.ids:
            if i["canonical"]: return i["raw"]
        return None

# ---------------------------------------------------------------- Ingestor
RANK={"explicit":3,"normalized":2,"inferred":1}

class Ingestor:
    def __init__(self,cat): self.cat=cat; self.resolver=Resolver(cat); self.event_log={}; self.idem={}
    def counts(self): return {"accepted":0,"quarantined":0,"duplicates":0,"corrected":0,"rejected":0}

    def ingest_records(self, source, batch_id, records, env=None, counts=None):
        c = counts if counts is not None else self.counts()
        for rec in records: self.apply(source,batch_id,rec,env or {},c)
        self.cat.prune()
        return c

    def ingest_event(self, ev, counts):
        env={"operation":ev.get("operation","upsert"),"update_mode":ev.get("update_mode"),
             "event_id":ev.get("event_id"),"idempotency_key":ev.get("idempotency_key"),
             "corrects":ev.get("corrects_listing_id"),"source_updated_at":ev.get("source_updated_at")}
        self.apply(ev.get("source"),None,ev.get("payload"),env,counts)
        return counts

    @staticmethod
    def mode(env):
        if env.get("update_mode"): return env["update_mode"]
        op=env.get("operation","upsert")
        return {"correct":"authoritative_correction","unavailable":"listing_tombstone",
                "delete":"listing_tombstone","patch":"partial_patch"}.get(op,"full_snapshot")

    def apply(self, source, batch_id, payload, env, c):
        mode=self.mode(env)
        payload_hash=sha1(json.dumps(payload,sort_keys=True))
        sel=select_adapter(source,payload)
        rid = sel[1](payload).record_id if sel else self.sniff(payload)
        # Request-level ids describe TRANSPORT of a multi-record batch: dedupe is record-scoped.
        scope = env.get("event_id") or batch_id or "batch"
        ekey = f"{source}|{scope}|{rid or payload_hash}"

        prior=self.event_log.get(ekey)
        if prior is not None:
            if prior==payload_hash: c["duplicates"]+=1; return
            self.quarantine(source,rid,"event_id_payload_conflict",payload,ekey); c["quarantined"]+=1; return
        if env.get("idempotency_key"):
            ik = f"{env['idempotency_key']}|{rid or payload_hash}"
            if self.idem.get(ik)==payload_hash: c["duplicates"]+=1; return
            self.idem[ik]=payload_hash
        self.event_log[ekey]=payload_hash

        if not sel:
            self.quarantine(source,rid,"no_adapter_for_schema",payload,ekey); c["quarantined"]+=1; return
        adapter_name, fn = sel
        x=fn(payload)
        if not x.record_id:
            self.quarantine(source,rid,"missing_source_record_id",payload,ekey); c["quarantined"]+=1; return

        target=None
        if mode=="authoritative_correction" and env.get("corrects"):
            target=self.cat.by_record(source,env["corrects"])
            if not target:
                for l in self.cat.listings.values():
                    if l.record_id==env["corrects"]: target=l; break
        if not target: target=self.cat.by_record(source,x.record_id)
        if not target:
            target=Listing(); target.source=source; target.record_id=x.record_id
            target.epoch=1+max([l.epoch for l in self.cat.listings.values()
                                if l.source==source and l.record_id==x.record_id]+[0])
            target.internal_id=f"L:{source}:{x.record_id}:{target.epoch}"
            self.cat.register(target)
        if mode=="authoritative_correction" and env.get("corrects"):
            self.cat.record_index[(source,x.record_id)]=target.internal_id

        self.merge(target,x,env,mode,ekey,adapter_name,payload)
        if mode=="listing_tombstone": target.lifecycle="inactive"
        elif target.lifecycle=="inactive": target.lifecycle="active"
        c["accepted"]+=1
        if mode=="authoritative_correction": c["corrected"]+=1
        self.resolver.resolve(target,True,ekey)
        self.update_offer(target,x,env,mode,ekey)

    def merge(self,l,x,env,mode,ekey,adapter_name,payload):
        src_time = env.get("source_updated_at") or x.source_updated_at or x.observed_at
        authoritative = mode=="authoritative_correction"
        hist={"event_key":ekey,"update_mode":mode,"operation":env.get("operation","upsert"),
              "observed_at":x.observed_at,"source_updated_at":src_time,
              "schema_version":adapter_name,"idempotency_key":env.get("idempotency_key")}
        l.history.append(hist)
        stale=False
        if not authoritative and l.source_updated_at and src_time:
            if any(f.get("authoritative") for f in l.fields.values()): stale=True
            elif src_time < l.source_updated_at: stale=True
        hist["applied"]=not stale
        if stale: return

        l.raw=payload; l.adapter=adapter_name; l.condition=x.condition or "new"
        for attr,val in (("seller",x.seller),("sku",x.sku),("title",x.title),("brand",x.brand),
                         ("content_origin",x.content_origin),("product_type",x.product_type),
                         ("observed_at",x.observed_at)):
            if val is not None: setattr(l,attr,val)
        l.source_updated_at=src_time

        if mode!="partial_patch":
            l.fields.clear(); l.provenance.clear(); l.ids.clear(); l.conflicts.clear()
            l.unknown.clear(); l.family=[]; l.code_family=[]; l.codes=[]

        for i in x.ids:
            l.ids.append(i)
            l.provenance.append({"canonical_field":i["ns"],"source_field":i["field"],"raw_value":i["raw"],
                "normalized_value":i["canonical"],"derivation":"normalized",
                "validity":"valid" if i["validity"]=="valid" else "invalid","scope":i["scope"],"event_key":ekey})

        for key,raw_val,src_field,raw_str,derivation in x.attrs:
            val=canon_value(key,raw_val)
            if val is None: continue
            known=key in CANONICAL
            prov={"canonical_field":key if known else None,"source_field":src_field,"raw_value":raw_str,
                  "normalized_value":str(val),"derivation":derivation,"event_key":ekey}
            if not known:
                l.unknown[key]=val; prov["validity"]="valid"; prov["retained_as"]="unknown_attribute"
                l.provenance.append(prov); continue
            ex=l.fields.get(key)
            if ex is None:
                l.fields[key]={"value":val,"state":"asserted","src_time":src_time,"event_key":ekey,
                               "derivation":derivation,"source_field":src_field,"authoritative":authoritative}
                prov["validity"]="valid"
            elif canon(ex["value"])==canon(val): prov["validity"]="valid"
            else:
                # Only equal-authority disagreement is a MATERIAL conflict. Structured/explicit
                # evidence outranks text parsed out of a title, so a lossy title inference never
                # forces REVIEW - it is retained as conflicting provenance and discarded as state.
                new_rank, old_rank = RANK.get(derivation,0), RANK.get(ex["derivation"],0)
                prov["validity"]="conflicting"
                if new_rank==old_rank: l.conflicts.add(key)
                if new_rank>old_rank:
                    l.fields[key]={"value":val,"state":"asserted","src_time":src_time,"event_key":ekey,
                                   "derivation":derivation,"source_field":src_field,"authoritative":authoritative}
            l.provenance.append(prov)

        for t in family_tokens(l.title):
            if t not in l.family: l.family.append(t)
        for t in full_codes(l.title):
            if t not in l.codes: l.codes.append(t)
        for i in l.ids:
            if i["ns"] in ("mpn","style_code") and i["raw"]:
                for t in family_tokens(str(i["raw"])):
                    if t not in l.family: l.family.append(t)
                sq=squeeze(str(i["raw"]))
                if sq and sq not in l.codes: l.codes.append(sq)
        l.code_family = list(l.family)
        if not l.family:
            l.family = fallback_tokens(l.title, l.brand)
        self.cat.union_all(l.family)
        l.category=self.cat.policies.infer(l.asserted_attrs(),l.title,l.brand)

    def update_offer(self,l,x,env,mode,ekey):
        m=x.money
        if not m: return
        cond=l.condition or "new"
        oid="of:"+sha1(f"{(l.seller or '').lower()}|{l.sku or l.internal_id}|{cond}")
        o=self.cat.offers.get(oid)
        if not o:
            o={"id":oid,"seller":l.seller,"condition":cond,"variant_id":None,"product_id":None,
               "active":True,"price":None,"list":None,"currency":"USD","price_kind":"total_purchase_price",
               "comparability":"UNKNOWN","terms":{},"observed_at":None,"listings":set(),"observations":[]}
            self.cat.offers[oid]=o
        o["listings"].add(l.record_id); o["product_id"]=l.product_id; o["variant_id"]=l.variant_id
        comp=self.comparability(m,l)
        ob={"event_key":ekey,"idempotency_key":env.get("idempotency_key"),"listing":l.record_id,
            "price":m["amount"],"list_price":m["list"],"currency":m["currency"],"price_kind":m["price_kind"],
            "comparability":comp,"terms":m["terms"],"availability":m["availability"],
            "observed_at":m["observed_at"],"variant_id_at_observation":l.variant_id,
            "universal_product_id_at_observation":l.product_id}
        o["observations"].append(ob)
        if o["observed_at"] is None or (m["observed_at"] and m["observed_at"]>=o["observed_at"]):
            o.update({"price":m["amount"],"list":m["list"],"currency":m["currency"],
                      "price_kind":m["price_kind"],"comparability":comp,"terms":m["terms"],
                      "observed_at":m["observed_at"]})
        o["active"]= mode!="listing_tombstone" and l.lifecycle!="inactive" and str(m["availability"]).lower()!="out_of_stock"

    @staticmethod
    def comparability(m,l):
        if m["comparability"]: return str(m["comparability"]).upper()
        if m["amount"] is None: return "UNKNOWN"
        if m["price_kind"]!="total_purchase_price": return "NOT_COMPARABLE"
        if m["terms"]: return "CONDITIONAL"
        if (l.condition or "new").lower()!="new": return "NOT_COMPARABLE"
        return "COMPARABLE"

    def quarantine(self,source,rid,reason,payload,ekey):
        rid = rid or "unidentified:"+sha1(json.dumps(payload,sort_keys=True))
        l=self.cat.by_record(source,rid)
        if not l:
            l=Listing(); l.source=source; l.record_id=rid; l.internal_id=f"L:{source}:{rid}:1"
            self.cat.register(l)
        l.quarantined=True; l.quarantine_reason=reason; l.raw=payload; l.decision="REVIEW"
        self.cat.quarantine.append({"source":source,"source_record_id":rid,"reason":reason,"event_key":ekey})
        self.resolver.resolve(l,True,ekey)

    @staticmethod
    def sniff(p):
        if not isinstance(p,dict): return None
        for k in ("record_id","eventId","event_id","observation_id","report_id","capture_id","id"):
            if p.get(k): return p[k]
        for v in p.values():
            if isinstance(v,dict):
                r=Ingestor.sniff(v)
                if r: return r
        return None

# ---------------------------------------------------------------- Exporter
def num(v):
    if isinstance(v,float) and v==int(v): return int(v)
    return v

def export(cat):
    listings=sorted(cat.listings.values(), key=lambda l:l.internal_id)
    normalized=[]
    for l in listings:
        row={"listing_id":l.record_id,"internal_listing_id":l.internal_id,"source":l.source,
             "source_record_id":l.record_id,"lifecycle_epoch":l.epoch,"lifecycle":l.lifecycle,
             "seller":l.seller,"merchant_sku":l.sku,"condition":l.condition,"adapter":l.adapter,
             "content_origin":l.content_origin,"product_type":l.product_type,
             "observed_at":l.observed_at,"source_updated_at":l.source_updated_at,
             "raw":l.raw or {}, "taxonomy":{"category":l.category},
             "normalized_attributes":{k:num(f["value"]) for k,f in l.fields.items() if f["state"]=="asserted"},
             "unknown_attributes":{k:num(v) for k,v in l.unknown.items()},
             "field_state":{k:{"state":f["state"],"source_updated_at":f["src_time"],
                               "event_key":f["event_key"],"derivation":f["derivation"],
                               "authoritative":f["authoritative"]} for k,f in l.fields.items()},
             "provenance":[dict(p, provenance_ref=f"{l.record_id}-prov-{i}") for i,p in enumerate(l.provenance)],
             "identifiers":[{"namespace":i["ns"],"raw":i["raw"],"canonical":i["canonical"],
                             "scope":i["scope"],"validity":i["validity"],"source_field":i["field"]} for i in l.ids],
             "source_history":l.history}
        if l.quarantined:
            row["quarantined"]=True; row["quarantine_reason"]=l.quarantine_reason
            row["quarantined_evidence"]=l.raw or {}
        normalized.append(row)

    rid=lambda iid: cat.listings[iid].record_id if iid in cat.listings else iid
    products=[{"id":p["id"],"taxonomy":{"category":p["category"]},
               "attributes":{k:num(v) for k,v in p["attrs"].items()},
               "source_listing_ids":sorted(rid(i) for i in p["listings"])}
              for p in sorted(cat.products.values(), key=lambda p:p["id"])]
    variants=[{"id":v["id"],"universal_product_id":v["product_id"],
               "attributes":{k:num(val) for k,val in v["attrs"].items()},
               "source_listing_ids":sorted(rid(i) for i in v["listings"])}
              for v in sorted(cat.variants.values(), key=lambda v:v["id"])]
    offers=[]; observations=[]
    for o in sorted(cat.offers.values(), key=lambda o:o["id"]):
        obs=[dict(ob, offer_id=o["id"]) for ob in o["observations"]]
        observations += obs
        offers.append({"id":o["id"],"variant_id":o["variant_id"],"universal_product_id":o["product_id"],
                       "seller":o["seller"],"condition":o["condition"],"active":o["active"],
                       "price":o["price"],"list_price":o["list"],"currency":o["currency"],
                       "price_kind":o["price_kind"],"comparability":o["comparability"],
                       "promotion_terms":o["terms"],"observed_at":o["observed_at"],
                       "source_listing_ids":sorted(o["listings"]),"observations":obs})
    decisions=[{"listing_id":l.record_id,"internal_listing_id":l.internal_id,
                "decision":l.decision or "REVIEW","universal_product_id":l.product_id,
                "variant_id":l.variant_id,"confidence":l.confidence,
                "positive_signals":l.pos,"negative_signals":l.neg,"hypotheses":l.hyp,
                "candidate_count":l.cand_count,"scored_candidate_count":len(l.scored),
                "scored_candidate_ids":sorted(l.scored),"candidate_sources":l.cand_sources,
                "lifecycle":l.lifecycle} for l in listings]
    catalog={"universal_products":products,"variants":variants,"offers":offers,
             "observations":observations,"resolution_history":cat.audits,
             "quarantine":cat.quarantine,
             "stats":{"catalog_entity_count":len(products)+len(variants)+len(offers),
                      "universal_product_count":len(products),"variant_count":len(variants),
                      "offer_count":len(offers),"listing_count":len(listings),
                      "quarantined_count":len(cat.quarantine)}}
    return normalized, catalog, decisions

# ---------------------------------------------------------------- driver
def load_initial():
    out=[]
    rows=list(csv.DictReader(open(DATA/"initial/affiliate_a.csv")))
    out.append(("affiliate_a","initial-affiliate-a",rows))
    ab=json.load(open(DATA/"initial/affiliate_b.json"))
    out.append(("affiliate_b","initial-affiliate-b",ab["products"]))
    ra=json.load(open(DATA/"initial/retailer_api.json"))
    out.append(("retailer_api","initial-retailer-api",ra["items"]))
    cd=json.load(open(DATA/"initial/community_deals.json"))
    out.append(("community_deals","initial-community-deals",cd))
    xo=json.load(open(DATA/"initial/extension_observations.json"))
    out.append(("extension_observations","initial-extension",xo["observations"]))
    return out

def run():
    policies=Policies(json.load(open(DATA/"IDENTITY_POLICY.json")))
    cat=Catalog(policies); ing=Ingestor(cat)
    counts=ing.counts()
    for source,batch,records in load_initial():
        ing.ingest_records(source,batch,records,{},counts)
    for phase in (1,2,3):
        doc=json.load(open(DATA/f"incremental/incremental_phase_{phase}.json"))
        events=doc["events"] if isinstance(doc,dict) else doc
        for ev in events: ing.ingest_event(ev,counts)
    cat.prune()
    return cat,counts

if __name__=="__main__":
    cat,counts=run()
    normalized,catalog,decisions=export(cat)
    outdir=(ROOT.parent/"outputs" if (ROOT.parent/"outputs").is_dir() else ROOT/"outputs"); outdir.mkdir(exist_ok=True)
    json.dump(normalized, open(outdir/"normalized_listings.json","w"), indent=2)
    json.dump(catalog, open(outdir/"catalog.json","w"), indent=2)
    json.dump(decisions, open(outdir/"resolution_decisions.json","w"), indent=2)
    print(json.dumps({"counts":counts, **catalog["stats"]}, indent=2))
