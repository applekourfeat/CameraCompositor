"""Create a local, randomized comparison tool. Model scores never label their own quality."""
import hashlib
import json
from pathlib import Path
from PIL import Image, ImageOps
from fetch_assets import ROOT
from evaluate import OUT, camera_boxes, crop, verify


PAGE = """<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src 'self' data: file:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'none'">
<title>Composition comparison</title><style>
body{font:16px system-ui,sans-serif;max-width:1100px;margin:24px auto;padding:0 16px;color:#171717;background:white}
button,select{font:inherit;margin:4px;padding:8px}button:focus-visible{outline:3px solid #111}
.pair{display:flex;gap:16px}.pair figure{margin:0;flex:1;min-width:0}.pair img{width:100%;height:400px;object-fit:contain;background:#eee}
#starting{max-width:100%;height:400px;object-fit:contain}textarea{display:block;width:95%;height:48px;font:inherit}
@media(max-width:600px){.pair img{height:240px}body{margin:12px auto}}
</style><h1>Composition comparison</h1>
<p>Compare the framing. Models and scores stay hidden. This page works locally and sends nothing.</p>
<label>Set <select id="split"><option value="development">Development</option><option value="evaluation">Evaluation</option></select></label>
<label>Task <select id="task"><option value="original">Compare suggestions</option><option value="control">Compare with fixed crop control</option><option value="labels">Label starting views</option></select></label>
<p id="progress" aria-live="polite"></p>
<section id="comparison"><div class="pair"><figure><figcaption>Left</figcaption><img id="left" alt="Left framing"></figure><figure><figcaption>Right</figcaption><img id="right" alt="Right framing"></figure></div>
<div id="choices"><button data-answer="left">Left is better</button><button data-answer="tie">Same quality</button><button data-answer="right">Right is better</button><button data-answer="unsure">Unsure</button></div></section>
<section id="labeling" hidden><img id="starting" alt="Starting view"><p>Does this starting view already have a good composition?</p><button data-label="good">Already good</button><button data-label="needs_work">Needs improvement</button><button data-label="unsure">Unsure</button></section>
<label>Notes <textarea id="notes" placeholder="Optional: cut subject, distracting edge, lost context..."></textarea></label>
<button id="previous">Previous</button><button id="next">Next / skip</button><button id="download">Download answers</button>
<p id="storage"></p><p id="scope">Only the separate analysis script can determine a result. The fixed crop control is a limited static comparison; it does not reproduce the old phone detector and pan guidance.</p>
<script>
const dataset=__DATA__;
const key='camera-composition-review-'+dataset.manifestHash;
let state={schema:1,manifestHash:dataset.manifestHash,answers:{},startingViews:{}},index=0;
try{const saved=JSON.parse(localStorage.getItem(key)||'null');if(saved&&saved.manifestHash===dataset.manifestHash)state=saved;}catch(e){}
const el=id=>document.getElementById(id);
if(dataset.tasks){
 el('split').replaceChildren(new Option('Evaluation','evaluation'));
 el('task').replaceChildren(...dataset.tasks.map(([value,label])=>new Option(label,value)));
 el('scope').textContent=dataset.scope;
}
function items(){const split=el('split').value,task=el('task').value;return task==='labels'?dataset.starts.filter(x=>x.split===split):dataset.trials.filter(x=>x.split===split&&x.type===task)}
function save(){try{localStorage.setItem(key,JSON.stringify(state));el('storage').textContent='Progress saved in this browser. Download answers to keep a portable copy.'}catch(e){el('storage').textContent='Browser storage is unavailable. Download answers before closing this page.'}}
function render(){const list=items();index=Math.min(Math.max(index,0),list.length-1);const item=list[index],labels=el('task').value==='labels';el('labeling').hidden=!labels;el('comparison').hidden=labels;
const records=labels?state.startingViews:state.answers;const done=list.filter(x=>records[x.id]).length;el('progress').textContent=`${index+1} of ${list.length}; ${done} answered in this task`;
el('notes').value=records[item.id]?.notes||'';if(labels){el('starting').src=item.image}else{el('left').src=item.a;el('right').src=item.b}
document.querySelectorAll('[data-answer],[data-label]').forEach(b=>b.setAttribute('aria-pressed',String((b.dataset.answer||b.dataset.label)===(records[item.id]?.answer))));}
function answer(value){const item=items()[index],records=el('task').value==='labels'?state.startingViews:state.answers;records[item.id]={answer:value,notes:el('notes').value.slice(0,1000)};save();if(index<items().length-1)index++;render()}
document.querySelectorAll('[data-answer]').forEach(b=>b.onclick=()=>answer(b.dataset.answer));document.querySelectorAll('[data-label]').forEach(b=>b.onclick=()=>answer(b.dataset.label));
el('split').onchange=el('task').onchange=()=>{index=0;render()};el('previous').onclick=()=>{index--;render()};el('next').onclick=()=>{index++;render()};
el('download').onclick=()=>{const blob=new Blob([JSON.stringify(state,null,2)],{type:'application/json'});const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='composition-review-answers.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)};
save();render();
</script></html>"""


def main():
    manifest_path = ROOT / "research" / "benchmark.json"
    manifest_hash = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
    scenes = json.loads(manifest_path.read_text(encoding="utf-8"))["scenes"]
    trials, keys, starts = [], [], []
    directory = OUT / "review-images"
    directory.mkdir(parents=True, exist_ok=True)

    def save_image(image, identity):
        name = hashlib.sha256(identity.encode()).hexdigest()[:20]+".jpg"
        ImageOps.contain(image, (720, 720)).save(directory / name, quality=92)
        return "review-images/" + name

    reports = {m: json.loads((OUT/f"{m}-results.json").read_text(encoding="utf-8")) for m in ["nima", "gaic"]}
    for model, report in reports.items():
        if report["manifestSha256"] != manifest_hash:
            raise ValueError(f"{model} evaluated a different manifest")
    for scene in scenes:
        original = verify(scene)
        starts.append({"id": scene["id"], "split": scene["split"], "image": save_image(original, scene["id"]+"/start")})
        for model, report in reports.items():
            row = next(r for r in report["results"] if r["sceneId"] == scene["id"])
            # Encode both sides once from the same decoded source. Extra JPEG generations
            # would give the reference an unfair technical-quality advantage.
            chosen = crop(original, camera_boxes()[row["cameraChoice"]])
            for kind in ["original", "control"]:
                identity = scene["id"]+"/"+model+"/"+kind
                digest = hashlib.sha256(identity.encode()).hexdigest()
                side = "left" if int(digest[-1], 16)%2 == 0 else "right"
                reference = original if kind == "original" else crop(original, camera_boxes()[3])
                a, b = (chosen, reference) if side == "left" else (reference, chosen)
                trials.append({"id": digest[:16], "split": scene["split"], "type": kind,
                    "a": save_image(a, identity+"/a"), "b": save_image(b, identity+"/b")})
                keys.append({"id": digest[:16], "sceneId": scene["id"], "model": model, "type": kind,
                    "candidateSide": side, "cameraChoice": row["cameraChoice"], "split": scene["split"]})
    trials.sort(key=lambda row: row["id"])
    data = {"manifestHash": manifest_hash, "trials": trials, "starts": starts}
    (OUT / "review.html").write_text(PAGE.replace("__DATA__", json.dumps(data)), encoding="utf-8")
    (OUT / "review-key.json").write_text(json.dumps({"manifestHash": manifest_hash, "trials": keys}, indent=2), encoding="utf-8")
    print(f"Created {len(trials)} randomized trials, with resumable local review: {OUT / 'review.html'}")


if __name__ == "__main__":
    main()
