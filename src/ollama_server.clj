(ns ollama-server
  (:require [org.httpkit.server :as hk]
            [cheshire.core :as json])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse
                          HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

;; --- config
(def ollama-url "http://localhost:11434")
(def client (HttpClient/newHttpClient))

(defn http-get [url]
  (let [req (-> (HttpRequest/newBuilder (URI. url)) (.GET) (.build))
        resp (.send client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp) :body (.body resp)}))

(defn http-post-json [url m]
  (let [body (json/generate-string m)
        req  (-> (HttpRequest/newBuilder (URI. url))
                 (.header "content-type" "application/json")
                 (.POST (HttpRequest$BodyPublishers/ofString body))
                 (.build))
        resp (.send client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp) :body (.body resp)}))

(def index-html
  "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>
  <title>Ollama Chat</title>
  <style>body{font-family:system-ui,sans-serif;margin:0;display:grid;grid-template-rows:auto 1fr auto;height:100vh}
  header{padding:.75rem 1rem;border-bottom:1px solid #ddd;display:flex;gap:.75rem;align-items:center}
  #log{padding:1rem;overflow-y:auto;background:#fafafa;display:flex;flex-direction:column}
  .msg{max-width:70ch;margin:.25rem 0;padding:.5rem .75rem;border-radius:.75rem;white-space:pre-wrap}
  .user{background:#e8f0ff;align-self:flex-end}.assistant{background:#f2f2f2}
  form{display:grid;grid-template-columns:1fr auto;gap:.5rem;padding:.75rem;border-top:1px solid #ddd}
  textarea{width:100%;min-height:3rem;resize:vertical}input[type=text]{width:16rem}button{padding:.5rem .9rem}</style>
  </head><body>
  <header><strong>Ollama Chat</strong>
    <label>Model: <input id='model' type='text' placeholder='e.g. llama3.1:8b'></label>
    <button id='refreshModels' title='List installed models'>List models</button>
    <span id='status' style='margin-left:auto;color:#666'></span></header>
  <div id='log'></div>
  <form id='composer'><textarea id='input' placeholder='Type your message…'></textarea><button id='send' type='submit'>Send</button></form>
  <script>
    const log=document.getElementById('log'),input=document.getElementById('input'),
          model=document.getElementById('model'),statusEl=document.getElementById('status'),
          form=document.getElementById('composer'),refreshBtn=document.getElementById('refreshModels');
    let messages=[];
    function addMsg(role,content){const d=document.createElement('div');d.className='msg '+(role==='user'?'user':'assistant');d.textContent=content;log.appendChild(d);log.scrollTop=log.scrollHeight;}
    async function chat(userText){
      const m=model.value.trim()||'llama3.1:8b';
      const body={model:m,messages:messages.concat([{role:'user',content:userText}])};
      statusEl.textContent='…thinking';
      const res=await fetch('/chat',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(body)});
      statusEl.textContent='';
      if(!res.ok){addMsg('assistant','Error: '+await res.text());return;}
      const data=await res.json();
      const assistant=(data&&data.message&&data.message.content)||'';
      messages.push({role:'user',content:userText});messages.push({role:'assistant',content:assistant});
      addMsg('assistant',assistant);
    }
    form.addEventListener('submit',async e=>{e.preventDefault();const t=input.value.trim();if(!t)return;addMsg('user',t);input.value='';await chat(t);input.focus();});
    refreshBtn.addEventListener('click',async()=>{try{const res=await fetch('/models');const list=await res.json();const names=(list.models||[]).map(m=>m.name).join('\\n');alert(names||'No models found.');}catch(e){alert('Failed to fetch models: '+e);}});
  </script></body></html>")

(defn ok [body & [ctype]] {:status 200 :headers {"content-type" (or ctype "text/plain; charset=utf-8")} :body body})
(defn ok-json [m] (ok (json/generate-string m) "application/json; charset=utf-8"))
(defn err-json [code m] (assoc (ok-json m) :status code))

(defn handler [{:keys [request-method uri body]}]
  (cond
    (and (= request-method :get) (= uri "/"))           (ok index-html "text/html; charset=utf-8")
    (and (= request-method :get) (= uri "/health"))     (ok-json {:ok true})
    (and (= request-method :get) (= uri "/models"))
    (let [{:keys [status body]} (http-get (str ollama-url "/api/tags"))]
      (if (<= 200 status 299) (ok body "application/json; charset=utf-8")
                              (err-json status {:error body})))

    (and (= request-method :post) (= uri "/chat"))
    (let [payload (try (json/parse-string (slurp body) true) (catch Exception _ nil))]
      (if (and (map? payload) (contains? payload :messages))
        (let [{:keys [status body]} (http-post-json (str ollama-url "/api/chat")
                                                    (merge {:stream false} payload))]
          (if (<= 200 status 299) (ok body "application/json; charset=utf-8")
                                  (err-json status (try (json/parse-string body true)
                                                        (catch Exception _ {:error body})))))
        (err-json 400 {:error "Expected JSON with {:model <string>, :messages <vector>}"})))

    :else (err-json 404 {:error "Not found"})))

(defn -main [& _]
  (println "Listening on http://localhost:3000")
  (hk/run-server handler {:port 3000 :threads 64 :queue-size 1024})
  @(promise))
