//! Neutered replacement resources served for `$redirect=` filters.
//! Script stubs keep pages working when their ad/analytics libraries are blocked.

pub struct Resource {
    pub mime: &'static str,
    pub body: Vec<u8>,
}

const GIF_1X1: &[u8] = &[
    0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0xff, 0xff, 0xff,
    0x21, 0xf9, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02,
    0x02, 0x44, 0x01, 0x00, 0x3b,
];

fn crc32(data: &[u8]) -> u32 {
    let mut crc = 0xffff_ffffu32;
    for &b in data {
        crc ^= b as u32;
        for _ in 0..8 {
            crc = if crc & 1 != 0 { (crc >> 1) ^ 0xedb8_8320 } else { crc >> 1 };
        }
    }
    !crc
}

fn adler32(data: &[u8]) -> u32 {
    let (mut a, mut b) = (1u32, 0u32);
    for &d in data {
        a = (a + d as u32) % 65521;
        b = (b + a) % 65521;
    }
    (b << 16) | a
}

/// Encodes a fully transparent RGBA PNG (zlib "stored" blocks, no compression needed).
pub fn transparent_png(w: u32, h: u32) -> Vec<u8> {
    let mut raw = Vec::with_capacity(((w * 4 + 1) * h) as usize);
    for _ in 0..h {
        raw.push(0);
        raw.extend(std::iter::repeat_n(0u8, (w * 4) as usize));
    }
    let mut z = vec![0x78, 0x01];
    let chunks: Vec<&[u8]> = raw.chunks(65535).collect();
    for (i, c) in chunks.iter().enumerate() {
        z.push(if i + 1 == chunks.len() { 1 } else { 0 });
        let len = c.len() as u16;
        z.extend_from_slice(&len.to_le_bytes());
        z.extend_from_slice(&(!len).to_le_bytes());
        z.extend_from_slice(c);
    }
    z.extend_from_slice(&adler32(&raw).to_be_bytes());

    let mut out = vec![0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a];
    let mut chunk = |kind: &[u8], data: &[u8]| {
        out.extend_from_slice(&(data.len() as u32).to_be_bytes());
        let mut c = kind.to_vec();
        c.extend_from_slice(data);
        out.extend_from_slice(&c);
        out.extend_from_slice(&crc32(&c).to_be_bytes());
    };
    let mut ihdr = Vec::new();
    ihdr.extend_from_slice(&w.to_be_bytes());
    ihdr.extend_from_slice(&h.to_be_bytes());
    ihdr.extend_from_slice(&[8, 6, 0, 0, 0]);
    chunk(b"IHDR", &ihdr);
    chunk(b"IDAT", &z);
    chunk(b"IEND", &[]);
    out
}

/// Two silent MPEG-1 Layer III frames (128 kbps, 44.1 kHz) ≈ 0.05 s.
fn silent_mp3() -> Vec<u8> {
    let mut v = Vec::with_capacity(836);
    for _ in 0..2 {
        v.extend_from_slice(&[0xff, 0xfb, 0x90, 0x64]);
        v.extend(std::iter::repeat_n(0u8, 413));
    }
    v
}

const NOOP_JS: &str = "(function(){})();";

const GA_JS: &str = r#"(function(){'use strict';
var noop=function(){};
var w=window,name=w.GoogleAnalyticsObject||'ga';
var ga=function(){var a=arguments,len=a.length;if(len===0)return;var f=a[len-1],o;
if(f instanceof Object&&f!==null&&typeof f.hitCallback==='function')o=f;else{for(var i=0;i<len;i++){if(a[i] instanceof Object&&typeof a[i].hitCallback==='function'){o=a[i];break;}}}
if(o){try{o.hitCallback();}catch(e){}}else if(typeof f==='function'){try{f({get:noop,set:noop,send:noop});}catch(e){}}};
ga.create=function(){return {get:noop,set:noop,send:noop};};ga.getByName=function(){return null;};ga.getAll=function(){return [];};ga.remove=noop;ga.loaded=true;
w[name]=ga;
var dl=w.dataLayer;if(dl instanceof Object){if(dl.hide instanceof Object&&typeof dl.hide.end==='function'){dl.hide.end();dl.hide.end=noop;}
if(typeof dl.push==='function'){var run=function(o){if(o instanceof Object===false)return;if(typeof o.eventCallback==='function'){setTimeout(o.eventCallback,1);o.eventCallback=noop;}};
dl.push=new Proxy(dl.push,{apply:function(t,a,args){run(args[0]);return Reflect.apply(t,a,args);}});
if(Array.isArray(dl)){var q=dl.slice();for(var j=0;j<q.length;j++)run(q[j]);}}}
if(typeof w.gtag!=='function')w.gtag=function(){var a=arguments[arguments.length-1];if(a instanceof Object&&typeof a.event_callback==='function'){setTimeout(a.event_callback,1);}};
var gaq=w._gaq;if(Array.isArray(gaq)===false){w._gaq={push:function(){for(var i=0;i<arguments.length;i++){if(typeof arguments[i]==='function'){try{arguments[i]();}catch(e){}}}}};}
w._gat={_getTracker:function(){return {_trackPageview:noop,_trackEvent:noop,_setAccount:noop,_setDomainName:noop,_setAllowLinker:noop,_getLinkerUrl:function(u){return u;}};},_getTrackerByName:function(){return w._gat._getTracker();},_anonymizeIp:noop,_createTracker:function(){return w._gat._getTracker();}};
})();"#;

const GPT_JS: &str = r#"(function(){'use strict';
var noop=function(){},noopthis=function(){return this;},noopnull=function(){return null;},nooparray=function(){return [];},noopstr=function(){return '';};
var companionAdsService={addEventListener:noopthis,enableSyncLoading:noop,setRefreshUnfilledSlots:noop};
var contentService={addEventListener:noopthis,setContent:noop};
var PassbackSlot=function(){};var p=PassbackSlot.prototype;p.display=noop;p.get=noopnull;p.set=noopthis;p.setClickUrl=noopthis;p.setTagForChildDirectedTreatment=noopthis;p.setTargeting=noopthis;p.updateTargetingFromMap=noopthis;
var pubAdsService={addEventListener:noopthis,clear:noop,clearCategoryExclusions:noopthis,clearTagForChildDirectedTreatment:noopthis,clearTargeting:noopthis,collapseEmptyDivs:noop,defineOutOfPagePassback:function(){return new PassbackSlot();},definePassback:function(){return new PassbackSlot();},disableInitialLoad:noop,display:noop,enableAsyncRendering:noop,enableLazyLoad:noop,enableSingleRequest:noop,enableSyncRendering:noop,enableVideoAds:noop,get:noopnull,getAttributeKeys:nooparray,getTargeting:nooparray,getTargetingKeys:nooparray,getSlots:nooparray,isInitialLoadDisabled:function(){return true;},refresh:noop,removeEventListener:noop,set:noopthis,setCategoryExclusion:noopthis,setCentering:noop,setCookieOptions:noopthis,setForceSafeFrame:noopthis,setLocation:noopthis,setPrivacySettings:noopthis,setPublisherProvidedId:noopthis,setRequestNonPersonalizedAds:noopthis,setSafeFrameConfig:noopthis,setTagForChildDirectedTreatment:noopthis,setTargeting:noopthis,setVideoContent:noopthis,updateCorrelator:noop};
var SizeMappingBuilder=function(){this.mapping=[];};SizeMappingBuilder.prototype.addSize=function(){return this;};SizeMappingBuilder.prototype.build=noopnull;
var Slot=function(){};var s=Slot.prototype;s.addService=noopthis;s.clearCategoryExclusions=noopthis;s.clearTargeting=noopthis;s.defineSizeMapping=noopthis;s.get=noopnull;s.getAdUnitPath=nooparray;s.getAttributeKeys=nooparray;s.getCategoryExclusions=nooparray;s.getDomId=noopstr;s.getResponseInformation=noopnull;s.getSlotElementId=noopstr;s.getSlotId=noopthis;s.getTargeting=nooparray;s.getTargetingKeys=nooparray;s.set=noopthis;s.setCategoryExclusion=noopthis;s.setClickUrl=noopthis;s.setCollapseEmptyDiv=noopthis;s.setTargeting=noopthis;s.updateTargetingFromMap=noopthis;
var gt=window.googletag||{};var cmd=gt.cmd||[];
gt.apiReady=true;gt.cmd=[];gt.cmd.push=function(a){try{a();}catch(e){}return 1;};
gt.companionAds=function(){return companionAdsService;};gt.content=function(){return contentService;};gt.defineOutOfPageSlot=function(){return new Slot();};gt.defineSlot=function(){return new Slot();};gt.destroySlots=noop;gt.disablePublisherConsole=noop;gt.display=noop;gt.enableServices=noop;gt.getVersion=noopstr;gt.pubads=function(){return pubAdsService;};gt.pubadsReady=true;gt.setAdIframeTitle=noop;gt.sizeMapping=function(){return new SizeMappingBuilder();};
window.googletag=gt;while(cmd.length!==0){try{gt.cmd.push(cmd.shift());}catch(e){}}
})();"#;

const ADSBYGOOGLE_JS: &str = r#"(function(){'use strict';
window.adsbygoogle={loaded:true,push:function(){}};
var phs=document.querySelectorAll('.adsbygoogle');
for(var i=0;i<phs.length;i++){var id='aswift_'+i;if(document.getElementById(id)!==null)continue;var fr=document.createElement('iframe');fr.id=id;fr.style.height=0;fr.style.width=0;phs[i].setAttribute('data-adsbygoogle-status','done');phs[i].appendChild(fr);}
})();"#;

const IMA_JS: &str = r#"(function(){'use strict';
if(window.google&&window.google.ima&&window.google.ima.__arivna)return;
var VERSION='3.453.0';
function EventHandler(){this.listeners=new Map();}
EventHandler.prototype._dispatch=function(e){var l=this.listeners.get(e.type)||[];l=Array.from(l);for(var i=0;i<l.length;i++){try{l[i].call(window,e);}catch(x){}}};
EventHandler.prototype.addEventListener=function(types,c){if(!Array.isArray(types))types=[types];for(var i=0;i<types.length;i++){var t=types[i];if(!this.listeners.has(t))this.listeners.set(t,new Set());this.listeners.get(t).add(c);}};
EventHandler.prototype.removeEventListener=function(types,c){if(!Array.isArray(types))types=[types];for(var i=0;i<types.length;i++){var s=this.listeners.get(types[i]);if(s)s.delete(c);}};
function AdError(m,c,t){this.message=m;this.errorCode=c;this.type=t;}
AdError.prototype.getErrorCode=function(){return this.errorCode;};AdError.prototype.getInnerError=function(){return null;};AdError.prototype.getMessage=function(){return this.message;};AdError.prototype.getType=function(){return this.type;};AdError.prototype.getVastErrorCode=function(){return 303;};AdError.prototype.toString=function(){return 'AdError '+this.errorCode+': '+this.message;};
AdError.ErrorCode={VAST_EMPTY_RESPONSE:1009,UNKNOWN_ERROR:900};AdError.Type={AD_LOAD:'adLoadError',AD_PLAY:'adPlayError'};
function AdErrorEvent(e,ctx){this.error=e;this.ctx=ctx;this.type=AdErrorEvent.Type.AD_ERROR;}
AdErrorEvent.prototype.getError=function(){return this.error;};AdErrorEvent.prototype.getUserRequestContext=function(){return this.ctx;};AdErrorEvent.Type={AD_ERROR:'adError'};
function AdsManager(){EventHandler.call(this);this.volume=1;}
AdsManager.prototype=Object.create(EventHandler.prototype);
['collapse','configureAdsManager','destroy','discardAdBreak','expand','focus','init','pause','resize','resume','setVolume','skip','stop','updateAdsRenderingSettings','clicked'].forEach(function(n){AdsManager.prototype[n]=function(){};});
AdsManager.prototype.start=function(){var self=this;setTimeout(function(){self._dispatch({type:'allAdsCompleted',getAd:function(){return null;}});self._dispatch({type:'contentResumeRequested',getAd:function(){return null;}});},5);};
AdsManager.prototype.getCuePoints=function(){return [];};AdsManager.prototype.getAdSkippableState=function(){return false;};AdsManager.prototype.getRemainingTime=function(){return 0;};AdsManager.prototype.getVolume=function(){return this.volume;};AdsManager.prototype.isCustomClickTrackingUsed=function(){return false;};AdsManager.prototype.isCustomPlaybackUsed=function(){return false;};
function AdDisplayContainer(){}AdDisplayContainer.prototype.destroy=function(){};AdDisplayContainer.prototype.initialize=function(){};
function ImaSdkSettings(){}
['getCompanionBackfill','getDisableCustomPlaybackForIOS10Plus','getFeatureFlags','getLocale','getNumRedirects','getPlayerType','getPlayerVersion','getPpid','isCookiesEnabled','isVpaidAdapter'].forEach(function(n){ImaSdkSettings.prototype[n]=function(){};});
['setAutoPlayAdBreaks','setCompanionBackfill','setCookiesEnabled','setDisableCustomPlaybackForIOS10Plus','setFeatureFlags','setLocale','setNumRedirects','setPlayerType','setPlayerVersion','setPpid','setVpaidAllowed','setVpaidMode','setSessionId','setDisableFlashAds'].forEach(function(n){ImaSdkSettings.prototype[n]=function(){};});
ImaSdkSettings.CompanionBackfillMode={ALWAYS:'always',ON_MASTER_AD:'on_master_ad'};ImaSdkSettings.VpaidMode={DISABLED:0,ENABLED:1,INSECURE:2};
function AdsLoader(){EventHandler.call(this);}
AdsLoader.prototype=Object.create(EventHandler.prototype);
AdsLoader.prototype.contentComplete=function(){};AdsLoader.prototype.destroy=function(){};AdsLoader.prototype.getSettings=function(){return new ImaSdkSettings();};AdsLoader.prototype.getVersion=function(){return VERSION;};
AdsLoader.prototype.requestAds=function(req,ctx){var self=this;setTimeout(function(){self._dispatch(new AdErrorEvent(new AdError('No ads available',AdError.ErrorCode.VAST_EMPTY_RESPONSE,AdError.Type.AD_LOAD),ctx));},5);};
function AdsManagerLoadedEvent(){}AdsManagerLoadedEvent.prototype.getAdsManager=function(){return new AdsManager();};AdsManagerLoadedEvent.Type={ADS_MANAGER_LOADED:'adsManagerLoaded'};
function AdsRenderingSettings(){}function AdsRequest(){}AdsRequest.prototype.setAdWillAutoPlay=function(){};AdsRequest.prototype.setAdWillPlayMuted=function(){};AdsRequest.prototype.setContinuousPlayback=function(){};
function AdEvent(){}AdEvent.Type={AD_BREAK_READY:'adBreakReady',ALL_ADS_COMPLETED:'allAdsCompleted',CLICK:'click',COMPLETE:'complete',CONTENT_PAUSE_REQUESTED:'contentPauseRequested',CONTENT_RESUME_REQUESTED:'contentResumeRequested',LOADED:'loaded',PAUSED:'pause',RESUMED:'resume',SKIPPED:'skip',STARTED:'start',USER_CLOSE:'userClose',VOLUME_CHANGED:'volumeChange',AD_PROGRESS:'adProgress',LOG:'log',FIRST_QUARTILE:'firstQuartile',MIDPOINT:'midpoint',THIRD_QUARTILE:'thirdQuartile',IMPRESSION:'impression'};
var ima={AdDisplayContainer:AdDisplayContainer,AdError:AdError,AdErrorEvent:AdErrorEvent,AdEvent:AdEvent,AdsLoader:AdsLoader,AdsManager:AdsManager,AdsManagerLoadedEvent:AdsManagerLoadedEvent,AdsRenderingSettings:AdsRenderingSettings,AdsRequest:AdsRequest,ImaSdkSettings:ImaSdkSettings,
UiElements:{AD_ATTRIBUTION:'adAttribution',COUNTDOWN:'countdown'},ViewMode:{FULLSCREEN:'fullscreen',NORMAL:'normal'},CompanionAdSelectionSettings:function(){},
settings:new ImaSdkSettings(),OmidAccessMode:{DOMAIN:'domain',FULL:'full',LIMITED:'limited'},VERSION:VERSION,__arivna:true};
window.google=window.google||{};window.google.ima=ima;
})();"#;

const APSTAG_JS: &str = r#"(function(){'use strict';var w=window,noop=function(){};
var apstag={_Q:[],fetchBids:function(a,b){if(typeof b==='function'){b([]);}},init:noop,setDisplayBids:noop,targetingKeys:function(){return [];},renderImp:noop,punt:noop,dpa:noop,rpa:noop,upa:noop};
w.apstag=apstag;})();"#;

const FINGERPRINT2_JS: &str = r#"(function(){'use strict';
var hash=String(Date.now()).split('').reverse().join('');
var F=function(o){if(!(this instanceof F))return new F(o);};
F.get=function(o,cb){if(typeof o==='function')cb=o;setTimeout(function(){cb([]);},1);};
F.getPromise=function(){return Promise.resolve([]);};F.getV18=function(o,cb){if(typeof o==='function')cb=o;setTimeout(function(){cb(hash,[]);},1);};
F.x64hash128=function(){return hash;};F.VERSION='2.1.0';
F.prototype={get:function(o,cb){if(typeof o==='function')cb=o;setTimeout(function(){cb(hash,[]);},1);}};
window.Fingerprint2=F;})();"#;

const PREBID_ADS_JS: &str = "(function(){'use strict';window.canRunAds=true;window.isAdBlockActive=false;})();";

const FAB_JS: &str = r#"(function(){'use strict';
var Fab=function(){};var p=Fab.prototype;
p.check=function(){};p.clearEvent=function(){};p.emitEvent=function(){};
p.on=function(a,b){if(!a)b();return this;};p.onDetected=function(){return this;};p.onNotDetected=function(a){a();return this;};p.setOption=function(){};p.options={set:function(){},get:function(){}};
var fab=new Fab();var d={get:function(){return Fab;},set:function(){}};var d2={get:function(){return fab;},set:function(){}};
try{Object.defineProperties(window,{FuckAdBlock:d,BlockAdBlock:d,SniffAdBlock:d,fuckAdBlock:d2,blockAdBlock:d2,sniffAdBlock:d2});}catch(e){}
})();"#;

const POPADS_JS: &str = "(function(){'use strict';delete window.PopAds;delete window.popns;try{Object.defineProperties(window,{PopAds:{value:{}},popns:{value:{}}});}catch(e){}})();";

const SCORECARD_JS: &str = "(function(){'use strict';window.COMSCORE={purge:function(){window._comscore=[];},beacon:function(){}};})();";

const OUTBRAIN_JS: &str = "(function(){'use strict';var noop=function(){};var obr={extern:{video:{getVideoRecs:noop,videoClicked:noop}}};var w=window;w.outbrain=obr;w.OBR=w.OBR||obr;})();";

const CHARTBEAT_JS: &str = "(function(){'use strict';var noop=function(){};window.pSUPERFLY={activity:noop,virtualPage:noop};})();";

const SENSORS_JS: &str = "(function(){'use strict';var noop=function(){};window.sensorsDataAnalytic201505={init:noop,quick:noop,register:noop,track:noop};})();";

const AD_STATUS_JS: &str = "window.google_ad_status=1;";

const VMAP: &str = r#"<vmap:VMAP xmlns:vmap="http://www.iab.net/videosuite/vmap" version="1.0"></vmap:VMAP>"#;

fn text(mime: &'static str, s: &str) -> Resource {
    Resource { mime, body: s.as_bytes().to_vec() }
}

/// Looks a redirect resource up by uBO name or alias.
pub fn get(name: &str) -> Option<Resource> {
    let js = "application/javascript";
    Some(match name {
        "noopjs" | "noop.js" | "blank-js" => text(js, NOOP_JS),
        "nooptext" | "noop.txt" | "empty" => text("text/plain", ""),
        "noopcss" | "noop.css" => text("text/css", ""),
        "noopjson" | "noop.json" => text("application/json", "{}"),
        "noopframe" | "noop.html" => text("text/html", "<!DOCTYPE html><html><head></head><body></body></html>"),
        "1x1.gif" | "1x1-transparent.gif" => Resource { mime: "image/gif", body: GIF_1X1.to_vec() },
        "2x2.png" | "2x2-transparent.png" => Resource { mime: "image/png", body: transparent_png(2, 2) },
        "3x2.png" | "3x2-transparent.png" => Resource { mime: "image/png", body: transparent_png(3, 2) },
        "32x32.png" | "32x32-transparent.png" => Resource { mime: "image/png", body: transparent_png(32, 32) },
        "noopmp3-0.1s" | "noop-0.1s.mp3" | "noop-0.5s.mp3" => Resource { mime: "audio/mpeg", body: silent_mp3() },
        "noopmp4-1s" | "noop-1s.mp4" => Resource { mime: "video/mp4", body: Vec::new() },
        "noopvast-2.0" | "noop-vast2.xml" => text("text/xml", "<VAST version=\"2.0\"></VAST>"),
        "noopvast-3.0" | "noop-vast3.xml" => text("text/xml", "<VAST version=\"3.0\"></VAST>"),
        "noopvast-4.0" | "noop-vast4.xml" => text("text/xml", "<VAST version=\"4.0\"></VAST>"),
        "noopvmap-1.0" | "noop-vmap1.0.xml" => text("text/xml", VMAP),
        "google-analytics_analytics.js" | "google-analytics.com/analytics.js" | "googletagmanager_gtm.js"
        | "googletagmanager.com/gtm.js" | "google-analytics_ga.js" | "google-analytics.com/ga.js"
        | "google-analytics.com" | "googletagmanager.com" | "google-analytics_inpage_linkid.js" => text(js, GA_JS),
        "googletagservices_gpt.js" | "googletagservices.com/gpt.js" | "googletagservices-gpt" => text(js, GPT_JS),
        "googlesyndication_adsbygoogle.js" | "googlesyndication.com/adsbygoogle.js" | "googlesyndication-adsbygoogle" => {
            text(js, ADSBYGOOGLE_JS)
        }
        "google-ima.js" | "google-ima3" | "ima3.js" => text(js, IMA_JS),
        "amazon_apstag.js" | "amazon-adsystem.com/aax2/apstag.js" => text(js, APSTAG_JS),
        "fingerprint2.js" => text(js, FINGERPRINT2_JS),
        "prebid-ads.js" => text(js, PREBID_ADS_JS),
        "nobab.js" | "nobab2.js" | "bab-defuser.js" | "fuckadblock.js-3.2.0" | "nofab.js" => text(js, FAB_JS),
        "popads.net.js" | "popads.js" | "popads-dummy.js" => text(js, POPADS_JS),
        "scorecardresearch_beacon.js" | "scorecardresearch.com/beacon.js" | "scorecardresearch.com" => text(js, SCORECARD_JS),
        "outbrain-widget.js" | "widgets.outbrain.com/outbrain.js" => text(js, OUTBRAIN_JS),
        "chartbeat.js" | "static.chartbeat.com/chartbeat.js" => text(js, CHARTBEAT_JS),
        "sensors-analytics.js" => text(js, SENSORS_JS),
        "doubleclick_instream_ad_status.js" | "doubleclick.net/instream/ad_status.js" => text(js, AD_STATUS_JS),
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    #[test]
    fn png_is_valid_shape() {
        let p = super::transparent_png(2, 2);
        assert_eq!(&p[1..4], b"PNG");
        assert!(p.len() > 50);
        assert!(super::get("noopjs").is_some());
        assert!(super::get("nope").is_none());
    }
}
