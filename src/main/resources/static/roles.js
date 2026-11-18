/* Phase 2: permissions remain enforced by Java; this script only improves UX. */
(async () => {
 'use strict';
 const el = id => document.getElementById(id);
 const r = await fetch('/api/me', {credentials:'same-origin'});
 if (r.redirected || !r.ok) return;
 const identity = await r.json();
 if (!identity.administrator) {
   document.body.classList.add('faceattend-faculty');
   const label = document.querySelector('.brand-subtitle');
   if (label) label.textContent = `Faculty workspace · ${identity.username}`;
   return;
 }
 el('role-admin-panel').hidden = false;
 const feedback = el('role-feedback');
 let csrf = null;
 function notice(message, isError=false) {
   feedback.textContent = message;
   feedback.classList.toggle('role-error', isError);
 }
 async function request(path, options = {}) {
   const init = {credentials:'same-origin', ...options, headers:{...(options.headers||{})}};
   if (init.method && init.method !== 'GET') {
     if (!csrf) {
       const tokenResponse = await fetch('/api/csrf', {credentials:'same-origin'});
       if (!tokenResponse.ok) throw Error('Sign in again');
       csrf = await tokenResponse.json();
     }
     init.headers[csrf.headerName] = csrf.token;
   }
   const response = await fetch(path,init);
   if (response.redirected) throw Error('Session expired; please sign in again');
   const body = await response.text();
   let result;
   try {result=JSON.parse(body);} catch {result={message:body};}
   if (!response.ok) throw Error(result.message||'Operation failed: '+response.status);
   return result;
 }
 function option(select, value, text) {
   const o=document.createElement('option');o.value=String(value);o.textContent=String(text);select.append(o);
 }
 let users=[], classrooms=[], subjects=[], assignments=[];
 async function load() {
   [users, classrooms, assignments] = await Promise.all([
     request('/api/admin/accounts'),request('/api/school/classrooms'),request('/api/admin/assignments')
   ]);
   const list=el('role-users');list.replaceChildren();
   for (const user of users) {
     const item=document.createElement('div');item.className='role-item';
     const info=document.createElement('div');
     const title=document.createElement('strong');title.textContent=`${user.username} · ${user.account_role}`;
     const subtitle=document.createElement('small');subtitle.textContent=`${user.email||'Email not set'} · ${user.active?'Active':'Disabled'}${user.roll?' · '+user.roll+' ('+user.name+')':''}`;
     info.append(title,subtitle);
     const actions=document.createElement('div');actions.className='role-actions';
     const change=document.createElement('button');change.type='button';change.className='role-outline';change.textContent='Reset password';
     change.addEventListener('click',()=>{
       const password=prompt('Enter a NEW 12+ character password for '+user.username+'. Share it privately.');
       if(password===null) return;
       run(async()=>{
         const response=await request('/api/admin/accounts/'+encodeURIComponent(user.username)+'/password',{
           method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({password})
         });notice(response.message);
       });
     });
     const status=document.createElement('button');status.type='button';status.className=user.active?'role-danger':'role-outline';status.textContent=user.active?'Disable':'Enable';
     status.addEventListener('click',()=>run(async()=>{
       if(!confirm(`${user.active?'Disable':'Enable'} account ${user.username}?`))return;
       await request('/api/admin/accounts/'+encodeURIComponent(user.username)+'/status',{
         method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({active:!user.active})
       });await load();notice(`Account ${user.username} ${user.active?'disabled':'enabled'}`);
     }));
     const remove=document.createElement('button');
     remove.type='button';remove.className='role-delete';remove.textContent='🗑 Delete';
     remove.setAttribute('aria-label','Delete login '+user.username);
     remove.title='Permanently delete this login only; attendance and face samples stay safe';
     remove.addEventListener('click',()=>run(async()=>{
       const agreed=window.confirm(`Delete LOGIN account ${user.username}?\n\nThis removes only this login and any faculty subject access. Student registration, saved face samples and attendance records are NOT deleted.\n\nContinue?`);
       if(!agreed)return;
       const result=await request('/api/admin/accounts/'+encodeURIComponent(user.username),{method:'DELETE'});
       await load();notice(result.message);
     }));
     const changeEmail=document.createElement('button'); changeEmail.type='button'; changeEmail.className='role-outline'; changeEmail.textContent='Set email';
     changeEmail.addEventListener('click',()=>{
       const email=prompt('Registered email for '+user.username+' (OTP will be delivered here):',user.email||'');
       if(email===null)return;
       run(async()=>{
         const response=await request('/api/admin/accounts/'+encodeURIComponent(user.username)+'/email',{
           method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email:email.trim()})
         });await load();notice(response.message);
       });
     });
     actions.append(changeEmail,change,status,remove);item.append(info,actions);list.append(item);
   }
   const facultySelect=el('role-faculty');facultySelect.replaceChildren();option(facultySelect,'','Select faculty account');
   for(const user of users.filter(u=>u.account_role==='FACULTY'&&u.active)) option(facultySelect,user.username,user.username);
   const classroomSelect=el('role-classroom');const old=classroomSelect.value;classroomSelect.replaceChildren();option(classroomSelect,'','Choose classroom');
   for(const c of classrooms) option(classroomSelect,c.id,`${c.name} · ${c.section}`);
   if(classrooms.some(c=>c.id===old)) classroomSelect.value=old;
   await loadSubjects();
   const assignmentList=el('role-assignments');assignmentList.replaceChildren();
   for (const a of assignments) {
     const row=document.createElement('div');row.className='role-item';
     const label=document.createElement('strong');label.textContent=`${a.username} · ${a.classroom_name} ${a.section} · ${a.subject_code}`;
     const remove=document.createElement('button');remove.type='button';remove.className='role-danger';remove.textContent='Remove access';
     remove.addEventListener('click',()=>run(async()=>{
       if(!confirm('Remove this faculty subject access?'))return;
       await request('/api/admin/assignments?'+new URLSearchParams({username:a.username,classroomId:a.classroom_id,subjectId:a.subject_id}),{method:'DELETE'});
       await load();notice('Faculty access removed');
     }));row.append(label,remove);assignmentList.append(row);
   }
   if(!assignments.length) assignmentList.textContent='No assignments yet. Assign a faculty member below.';
 }
 async function loadSubjects() {
   const id=el('role-classroom').value;
   const select=el('role-subject');select.replaceChildren();option(select,'','Choose classroom subject');
   if (!id) return;
   const subjects=await request('/api/school/classrooms/'+encodeURIComponent(id)+'/subjects');
   for(const s of subjects) option(select,s.id,`${s.code} — ${s.title}`);
 }
 function run(work) {Promise.resolve().then(work).catch(e=>notice(e.message||'Request failed',true));}
 function bind(id,work) {el(id).addEventListener('submit',e=>{e.preventDefault();run(work);});}
 el('role-classroom').addEventListener('change',()=>run(loadSubjects));
 const updateRole=()=>{
   const student=el('role-type').value==='STUDENT';
   el('role-student-row').hidden=!student;
   const opt=el('role-student-id').selectedOptions[0];
   if(student && opt?.dataset.roll)el('role-username').value='student.'+opt.dataset.roll.toLowerCase();
   else if(!student && el('role-username').value.startsWith('student.'))el('role-username').value='';
 };
 el('role-type').addEventListener('change',updateRole);
 el('role-student-id').addEventListener('change',updateRole);
 updateRole();
 const students=await request('/api/students');
 for(const raw of students) {
   const s=Object.fromEntries(Object.entries(raw).map(([k,v])=>[k.toLowerCase(),v]));
   const opt=document.createElement('option');opt.value=s.id;opt.textContent=`${s.roll} · ${s.name}`;opt.dataset.roll=s.roll;el('role-student-id').append(opt);
 }
 bind('role-create',async()=>{
   const payload={username:el('role-username').value.trim(),email:el('role-email').value.trim(),role:el('role-type').value,password:el('role-password').value};
   if(payload.role==='STUDENT')payload.studentId=el('role-student-id').value;
   const result=await request('/api/admin/accounts',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
   el('role-password').value=''; el('role-email').value=''; await load();notice(`Account created: ${result.username} (${result.role})`);
 });
 bind('role-assign',async()=>{
   const response=await request('/api/admin/assignments',{method:'POST',headers:{'Content-Type':'application/json'},
     body:JSON.stringify({username:el('role-faculty').value,classroomId:el('role-classroom').value,subjectId:el('role-subject').value})});
   await load();notice(response.message);
 });
  run(load);
  async function transferPanel() {
    const host = el('role-admin-panel');
    if (!host) return;
    const card = document.createElement('section');
    card.className = 'role-item';
    const title = document.createElement('strong');
    title.textContent = 'Transfer existing student to a Teacher (Admin only)';
    const help = document.createElement('p');
    help.textContent = 'Use only when an old roll is already registered. Student face samples and attendance stay linked to the student. The previous Teacher loses access.';
    const studentSelect = document.createElement('select');
    const teacherSelect = document.createElement('select');
    studentSelect.setAttribute('aria-label','Student to transfer');
    teacherSelect.setAttribute('aria-label','New owner Teacher');
    option(studentSelect,'','Choose existing student');
    option(teacherSelect,'','Choose new owner Teacher');
    const [records,accounts] = await Promise.all([request('/api/students'),request('/api/admin/accounts')]);
    for (const raw of records) {
      const st=Object.fromEntries(Object.entries(raw).map(([k,v])=>[k.toLowerCase(),v]));
      option(studentSelect,st.id,st.roll+' · '+st.name+' · current: '+(st.owner_username||'teacher'));
    }
    for(const a of accounts.filter(x=>x.account_role==='FACULTY'&&x.active))
      option(teacherSelect,a.username,a.username);
    const move = document.createElement('button');
    move.type='button'; move.textContent='Transfer student ownership';
    move.className='role-outline';
    move.addEventListener('click',()=>run(async()=>{
      if (!studentSelect.value || !teacherSelect.value) throw Error('Choose a student and a Teacher');
      if (!confirm('Transfer this student and all linked face samples to '+teacherSelect.value+'?')) return;
      move.disabled=true;
      try {
        const response=await request('/api/admin/students/'+encodeURIComponent(studentSelect.value)+'/owner',{
          method:'POST',headers:{'Content-Type':'application/json'},
          body:JSON.stringify({teacherUsername:teacherSelect.value})
        });
        notice(response.message);
        location.reload();
      } finally {move.disabled=false;}
    }));
    card.append(title,help,studentSelect,teacherSelect,move);
    host.append(card);
  }
  run(transferPanel);
 })();

/* FaceAttend role scenes V3 */
;(()=>{
function initRoleScenes(){const VERT="attribute vec2 aPos;void main(){gl_Position=vec4(aPos,0.0,1.0);}",FRAG="precision highp float;\nuniform vec2 uRes;\nuniform float uTime;\nuniform float uMix;\nuniform vec2 uPtr;\nuniform float uPtrOn;\nuniform vec3 uAccent;\n\nfloat hash(vec2 p){ p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }\nfloat noise(vec2 p){\n  vec2 i = floor(p); vec2 f = fract(p); vec2 u = f * f * (3.0 - 2.0 * f);\n  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);\n}\nfloat fbm(vec2 p){ float v = 0.0; float a = 0.5; for (int i = 0; i < 4; i++){ v += a * noise(p); p = p * 2.03 + 17.0; a *= 0.5; } return v; }\nvec3 spectrum(float x){ return 0.5 + 0.5 * cos(6.28318 * (x + vec3(0.0, 0.33, 0.67))); }\n\nvec3 mosaic(vec2 frag){\n  float cell = max(uRes.y / 30.0, 9.0);\n  vec2 id = floor(frag / cell);\n  vec2 f = fract(frag / cell);\n  vec2 p = (id + 0.5) * cell / uRes.y;\n  float ax = uRes.x / uRes.y;\n  float t = uTime * 0.07;\n  vec2 l1 = vec2(ax * 0.5 + 0.38 * sin(t * 2.1), 0.56 + 0.14 * cos(t * 1.7));\n  vec2 l2 = vec2(ax * 0.5 - 0.55 * cos(t * 1.3), 0.2 + 0.1 * sin(t * 2.3));\n  vec2 l3 = vec2(ax * 0.5 + 0.62 * cos(t * 0.9 + 2.0), 0.86 + 0.08 * sin(t * 1.1));\n  float L = 0.62 * exp(-2.6 * dot(p - l1, p - l1)) + 0.42 * exp(-3.8 * dot(p - l2, p - l2)) + 0.3 * exp(-5.0 * dot(p - l3, p - l3));\n  L += uPtrOn * 0.5 * exp(-14.0 * dot(p - uPtr, p - uPtr));\n  float n = fbm(p * 3.0 + vec2(t * 1.6, -t * 1.1));\n  L *= 0.45 + n;\n  float h = hash(id);\n  L *= 0.8 + 0.28 * sin(uTime * (0.5 + h * 1.4) + h * 40.0);\n  L = clamp(L, 0.0, 1.0);\n  L = floor(L * 8.0 + h * 0.6) / 8.0;\n  vec2 q = abs(f - 0.5);\n  float tile = (1.0-smoothstep(0.41, 0.47, max(q.x, q.y)));\n  vec3 cool = vec3(0.60, 0.70, 0.86);\n  vec3 warm = vec3(0.96, 0.88, 0.78);\n  vec3 col = mix(cool, warm, smoothstep(0.25, 0.75, n)) * L * 0.6;\n  col = mix(col, uAccent * L * 0.75, 0.2 * L);\n  col *= tile * (0.82 + 0.36 * f.y);\n  return col + vec3(0.010, 0.010, 0.014);\n}\n\nvec3 prism(vec2 frag){\n  vec2 p = frag / uRes.y;\n  float t = uTime * 0.05;\n  vec3 col = mix(vec3(0.06, 0.08, 0.13), vec3(0.17, 0.22, 0.31), smoothstep(-0.2, 1.3, p.y + p.x * 0.15));\n  float d = dot(p, normalize(vec2(1.0, -0.58)));\n  float warp = fbm(vec2(d * 1.2 - t, p.y * 0.8 + t)) * 0.7;\n  float bands = d * 2.6 + warp - t * 1.6;\n  float s = fract(bands);\n  float facet = smoothstep(0.0, 0.03, s) * (1.0 - smoothstep(0.03, 0.6, s));\n  vec3 spec = spectrum(bands * 0.31 + p.y * 0.25 + t * 0.5);\n  float beam = exp(-pow(abs((d - (0.55 + 0.35 * sin(t * 2.0))) * 2.4), 2.0));\n  col += spec * facet * (0.09 + 0.3 * beam);\n  col += spec * beam * 0.06;\n  float glint = pow(max(0.0, 1.0 - abs(s - 0.02) * 30.0), 3.0) * beam;\n  col += vec3(0.9, 0.95, 1.0) * glint * 0.22;\n  col += uAccent * 0.03;\n  return col;\n}\n\nvec3 waves(vec2 frag){\n  vec2 p = frag / uRes.y;\n  float t = uTime * 0.11;\n  vec3 col = mix(vec3(0.07, 0.04, 0.08), vec3(0.20, 0.11, 0.19), smoothstep(0.0, 1.1, p.y));\n  for (int i = 0; i < 5; i++){\n    float fi = float(i);\n    float y = 0.52 + (fi - 2.0) * 0.055\n      + 0.16 * sin(p.x * (1.5 + fi * 0.3) + t * (1.0 + fi * 0.22) + fi * 1.7)\n      + 0.05 * sin(p.x * 3.7 - t * 1.4 + fi * 2.3);\n    float dist = abs(p.y - y);\n    vec3 c = spectrum(fi * 0.16 + p.x * 0.12 - t * 0.25);\n    col += c * (exp(-dist * 42.0) * 0.28 + exp(-dist * 7.0) * 0.05);\n  }\n  col *= 0.88 + 0.24 * fbm(p * 2.2 + t);\n  return col;\n}\n\nvoid main(){\n  vec2 frag = gl_FragCoord.xy;\n  float m = clamp(uMix, 0.0, 2.0);\n  vec3 a; vec3 b = vec3(0.0); float k;\n  if (m < 1.0) { k = m; a = mosaic(frag); if (k > 0.001) b = prism(frag); }\n  else { k = m - 1.0; a = prism(frag); if (k > 0.001) b = waves(frag); }\n  float cell = max(uRes.y / 30.0, 9.0);\n  float thr = hash(floor(frag / cell) + 7.0) * 0.65 + (frag.y / uRes.y) * 0.35;\n  float w = smoothstep(thr - 0.06, thr + 0.06, k * 1.12 - 0.06);\n  vec3 col = mix(a, b, w);\n  vec2 uv = frag / uRes;\n  float vig = (1.0-smoothstep(0.25,1.25,length((uv-0.5)*vec2(1.0,1.15))));\n  col *= 0.55 + 0.45 * vig;\n  col += (hash(frag + fract(uTime)) - 0.5) / 255.0;\n  gl_FragColor = vec4(col, 1.0);\n}";

const canvas=document.querySelector('#fa-role-sky'),pause=document.querySelector('#fa-role-play');
if (!canvas || !pause) return;
const choices=[...document.querySelectorAll('#fa-role-scenes [data-choice]')];
let original=false, fallback=false;
let target=0,from=0,mix=0,elapsed=0,start=0,last=null,frame=0,on=0,glow=0,x=.5,y=.5;
let paused=matchMedia('(prefers-reduced-motion: reduce)').matches,paint=()=>{};
choices.forEach((b,i)=>b.onclick=()=>{
 original=i===3;
 document.body.toggleAttribute('data-fa-sky',!original);
 canvas.hidden=original;pause.hidden=original;
 if(!original){from=mix;target=i;start=elapsed;document.body.dataset.faScene=i}
 if(fallback){canvas.style.animationPlayState=(paused||original)?'paused':'running'}else{sync()}

 choices.forEach((c,j)=>c.setAttribute('aria-pressed',String(i===j)));
 if(paused&&!original){from=mix=i;paint()}
});
function draw(now){
 if(last!==null)elapsed+=(now-last)/1000;
 last=now;mix=from+(target-from)*(1-Math.pow(1-Math.min(1,(elapsed-start)/1.2),3));
 glow+=(on-glow)*.08;paint();frame=requestAnimationFrame(draw)
}
function sync(){cancelAnimationFrame(frame);last=null;pause.textContent=paused?'Play motion':'Pause motion';if(!paused&&!original&&!document.hidden)frame=requestAnimationFrame(draw)}
function setup(){
 const gl=canvas.getContext('webgl',{antialias:false,powerPreference:'low-power'});if(!gl)throw Error('WebGL unavailable');
 const prog=gl.createProgram();
 for(const [type,src] of [[gl.VERTEX_SHADER,VERT],[gl.FRAGMENT_SHADER,FRAG]]){
 const shader=gl.createShader(type);gl.shaderSource(shader,src);gl.compileShader(shader);if(!gl.getShaderParameter(shader,gl.COMPILE_STATUS))throw Error(gl.getShaderInfoLog(shader));gl.attachShader(prog,shader);gl.deleteShader(shader)}
 gl.linkProgram(prog);if(!gl.getProgramParameter(prog,gl.LINK_STATUS))throw Error(gl.getProgramInfoLog(prog));gl.useProgram(prog);
 gl.bindBuffer(gl.ARRAY_BUFFER,gl.createBuffer());gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,3,-1,-1,3]),gl.STATIC_DRAW);
 const pos=gl.getAttribLocation(prog,'aPos');gl.enableVertexAttribArray(pos);gl.vertexAttribPointer(pos,2,gl.FLOAT,false,0,0);
 const u=Object.fromEntries(['Res','Time','Ptr','PtrOn','Mix','Accent'].map(k=>[k,gl.getUniformLocation(prog,'u'+k)]));
 gl.uniform3f(u.Accent,.55,.78,1);
 paint=()=>{gl.uniform1f(u.Time,elapsed);gl.uniform1f(u.Mix,mix);gl.uniform2f(u.Ptr,x,y);gl.uniform1f(u.PtrOn,glow);gl.drawArrays(gl.TRIANGLES,0,3)};
 function size(){const s=Math.min(devicePixelRatio,1.5)*.75;canvas.width=Math.max(1,Math.round(canvas.clientWidth*s));canvas.height=Math.max(1,Math.round(canvas.clientHeight*s));gl.viewport(0,0,canvas.width,canvas.height);gl.uniform2f(u.Res,canvas.width,canvas.height);paint()}
 window.addEventListener('pointermove',e=>{x=e.clientX/canvas.clientHeight;y=1-e.clientY/canvas.clientHeight;on=1});
 document.documentElement.addEventListener('pointerleave',()=>on=0);
 window.addEventListener('resize',size);document.addEventListener('visibilitychange',sync);pause.onclick=()=>{paused=!paused;sync()};size();sync()
}
document.body.setAttribute("data-fa-sky","");
try{setup()}catch(e){fallback=true;canvas.classList.add('mosaic-software');
 function syncFallback(){
  canvas.style.animationPlayState=paused?'paused':'running';
  pause.textContent=paused?'Play motion':'Pause motion';
 }
 pause.onclick=()=>{paused=!paused;syncFallback()};syncFallback();console.warn(e)}
}
if(document.readyState==='loading') document.addEventListener('DOMContentLoaded',initRoleScenes,{once:true});else initRoleScenes();
})();