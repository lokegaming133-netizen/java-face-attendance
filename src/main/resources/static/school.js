/* Smart Classroom Phase 1: runs beside the original app.js without replacing camera/theme handlers. */
(() => {
  'use strict';
  const root = document.getElementById('school-section');
  if (!root) return;
  const $ = id => document.getElementById(id);
  const tabButtons = [...root.querySelectorAll('[data-school-tab]')];
  const tabNames = ['attendance', 'classes', 'reports'];
  function showTab(name) {
    if (!tabNames.includes(name)) return;
    for (const button of tabButtons) {
      const selected = button.dataset.schoolTab === name;
      button.classList.toggle('is-active', selected);
      button.setAttribute('aria-selected', String(selected));
    }
    for (const tab of tabNames) $('school-pane-' + tab).hidden = tab !== name;
    if (name === 'reports') scheduleReport();
  }
  tabButtons.forEach(b => b.addEventListener('click', () => showTab(b.dataset.schoolTab)));

  const state = { classrooms: [], subjects: [], students: [], assignments: [], roster: [], sessions: [] };
  let csrfToken = null, refreshingReport = false, reportTimer = null;

  function notice(message, error = false) {
    const el = $('school-message');
    el.textContent = message;
    el.classList.toggle('school-error', Boolean(error));
  }

  async function call(path, options = {}) {
    const init = { ...options, headers: { ...(options.headers || {}) } };
    if (init.method && init.method !== 'GET') {
      if (!csrfToken) {
        const response = await fetch('/api/csrf', { credentials: 'same-origin' });
        if (!response.ok || response.redirected) throw new Error('Please sign in again');
        csrfToken = await response.json();
      }
      init.headers[csrfToken.headerName] = csrfToken.token;
    }
    const response = await fetch('/api/school' + path, { credentials: 'same-origin', ...init });
    if (response.redirected) { location.href = '/login'; throw new Error('Please sign in'); }
    const content = await response.text();
    let data;
    try { data = JSON.parse(content); } catch { data = { message: content }; }
    if (!response.ok) throw new Error(data.message || `Request failed (${response.status})`);
    return data;
  }

  const post = (path, value) => call(path, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value)
  });

  function selectedClass() { return $('school-classroom').value; }
  function selectedSession() { return state.sessions.find(s => s.id === $('school-session').value); }

  function options(select, items, id, label, placeholder = 'Select an option') {
    const old = select.value;
    select.replaceChildren();
    const first = document.createElement('option');
    first.value = ''; first.textContent = placeholder;
    select.append(first);
    for (const item of items) {
      const option = document.createElement('option');
      option.value = String(item[id]);
      option.textContent = label(item);
      select.append(option);
    }
    if (items.some(item => String(item[id]) === old)) select.value = old;
  }

  function textNode(text, className) {
    const node = document.createElement('span');
    node.textContent = String(text);
    if (className) node.className = className;
    return node;
  }

  function localDate() {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  }

  function run(button, task) {
    const previous = button?.disabled;
    if (button) button.disabled = true;
    return Promise.resolve().then(task)
      .catch(error => notice(error?.message || 'Unexpected request error', true))
      .finally(() => { if (button) button.disabled = previous; });
  }

  async function reloadStudents() {
    const raw = await fetch('/api/students', { credentials: 'same-origin' });
    if (raw.redirected || !raw.ok) throw new Error('Unable to load registered students');
    state.students = (await raw.json()).map(student => Object.fromEntries(
      Object.entries(student).map(([key, value]) => [key.toLowerCase(), value])));
    options($('school-student-catalog'), state.students, 'id',
      s => `${s.roll} — ${s.name}`, 'Select a registered student');
  }

  async function reloadCatalog(preferClass = null) {
    const [classrooms, subjects] = await Promise.all([call('/classrooms'), call('/subjects')]);
    state.classrooms = classrooms; state.subjects = subjects;
    options($('school-classroom'), classrooms, 'id',
      c => `${c.name} · Section ${c.section} · ${c.academic_year}`, 'Select a classroom');
    if (preferClass && classrooms.some(c => c.id === preferClass)) $('school-classroom').value = preferClass;
    else if (!$('school-classroom').value && classrooms.length) $('school-classroom').value = classrooms[0].id;
    options($('school-subject-catalog'), subjects, 'id',
      s => `${s.code} — ${s.title}`, 'Choose from subject catalog');
    await reloadClass();
  }

  function drawRoster() {
    $('school-roster-count').textContent = String(state.roster.length);
    const rootList = $('school-roster');
    rootList.replaceChildren();
    if (!state.roster.length) {
      rootList.append(textNode('No students assigned to this classroom yet.', 'school-empty'));
      return;
    }
    for (const student of state.roster) {
      const row = document.createElement('div'); row.className = 'school-student';
      const info = document.createElement('div'); info.className = 'school-student-info';
      info.append(textNode(student.name));
      const roll = document.createElement('small'); roll.textContent = student.roll; info.append(roll);
      const remove = document.createElement('button'); remove.type = 'button';
      remove.className = 'school-danger'; remove.textContent = 'Remove';
      remove.setAttribute('aria-label', `Remove ${student.roll} from classroom`);
      remove.addEventListener('click', () => {
        if (!confirm(`Remove ${student.roll} from classroom roster? Student profile and face templates will remain.`)) return;
        run(remove, async () => {
          const data = await call(`/classrooms/${selectedClass()}/students/${student.id}`, { method: 'DELETE' });
          notice(data.message);
          await reloadClass();
        });
      });
      row.append(info, remove); rootList.append(row);
    }
  }

  function drawAssignments() {
    const list = $('school-subject-list'); list.replaceChildren();
    if (!state.assignments.length) {
      list.append(textNode('No subjects assigned.', 'school-empty'));
    }
    for (const s of state.assignments) {
      const chip = document.createElement('span'); chip.className = 'school-chip';
      const bold = document.createElement('b'); bold.textContent = `${s.code} · ${s.title}`;
      chip.append(bold, textNode(`Faculty: ${s.faculty_name}`)); list.append(chip);
    }
    options($('school-session-subject'), state.assignments, 'id',
      s => `${s.code} — ${s.title}`, state.assignments.length ? 'Choose a subject' : 'Assign a subject under Manage Classes');
    if (!$('school-session-subject').value && state.assignments.length) {
      $('school-session-subject').value = String(state.assignments[0].id);
    }
  }

  function drawSessions(prefer = null) {
    const selector = $('school-session');
    options(selector, state.sessions, 'id', s =>
      `${s.session_date} · P${s.period_number} · ${s.subject_code} · ${s.status}`,
      'Choose a saved session');
    if (prefer && state.sessions.some(s => s.id === prefer)) selector.value = prefer;
    else if (!selector.value && state.sessions.length) selector.value = state.sessions[0].id;
    updateSession();
  }

  async function reloadClass(preferSession = null) {
    const id = selectedClass();
    if (!id) {
      state.assignments = []; state.roster = []; state.sessions = [];
      drawAssignments(); drawRoster(); drawSessions();
      $('school-context-status').textContent = 'No classroom selected';
      return;
    }
    const [subjects, students, sessions] = await Promise.all([
      call(`/classrooms/${id}/subjects`),
      call(`/classrooms/${id}/students`),
      call(`/classrooms/${id}/sessions`)
    ]);
    state.assignments = subjects; state.roster = students; state.sessions = sessions;
    drawAssignments(); drawRoster(); drawSessions(preferSession);
    $('school-context-status').textContent = `${state.roster.length} students · ${state.assignments.length} subjects`;
    notice(`Classroom ready · ${state.roster.length} students · ${state.assignments.length} subjects`);
  }

  function updateSession() {
    const session = selectedSession();
    const active = $('school-active');
    const use = $('school-use'); const close = $('school-close'); const exportLink = $('school-export');
    use.disabled = !session || session.status !== 'OPEN';
    close.disabled = !session || session.status !== 'OPEN';
    exportLink.setAttribute('aria-disabled', String(!session));
    exportLink.href = session ? `/api/school/sessions/${encodeURIComponent(session.id)}/export` : '#school-section';
    if (!session) {
      active.textContent = 'No class attendance session selected.';
      $('school-report-body').replaceChildren();
      $('school-present').textContent = '0 / 0 present';
      return;
    }
    active.textContent = `${session.subject_code} · ${session.session_date} · Period ${session.period_number} · ${session.status}. ${session.status === 'OPEN' ? 'Click Use for face attendance.' : 'Closed sessions are read-only.'}`;
    scheduleReport();
  }

  function scheduleReport() {
    clearTimeout(reportTimer);
    if (selectedSession()) reportTimer = setTimeout(() => run(null, showReport), 300);
  }

  async function showReport() {
    if (refreshingReport) return;
    const session = selectedSession(); if (!session) return;
    refreshingReport = true;
    try {
      const report = await call(`/sessions/${session.id}/report`);
      if (selectedSession()?.id !== session.id) return;
      const body = $('school-report-body'); body.replaceChildren();
      $('school-present').textContent = `${report.present} / ${report.total} present`;
      if (!report.roster?.length) {
        const row = document.createElement('tr'); const col = document.createElement('td');
        col.colSpan = 4; col.textContent = 'No enrolled students.'; row.append(col); body.append(row);
      }
      for (const entry of report.roster || []) {
        const row = document.createElement('tr');
        for (const value of [entry.roll, entry.name]) {
          const cell = document.createElement('td'); cell.textContent = String(value || ''); row.append(cell);
        }
        const statusCell = document.createElement('td'); const badge = document.createElement('span');
        const present = entry.attendance_status === 'PRESENT';
        badge.className = 'school-pill' + (present ? '' : ' school-pending');
        badge.textContent = present ? 'Present' : 'Not marked';
        statusCell.append(badge); row.append(statusCell);
        const time = document.createElement('td'); time.textContent = entry.marked_at || '—'; row.append(time);
        body.append(row);
      }
    } finally { refreshingReport = false; }
  }

  function submitForm(id, handler) {
    $(id).addEventListener('submit', event => {
      event.preventDefault();
      const button = event.currentTarget.querySelector('button[type="submit"]');
      run(button, handler);
    });
  }

  submitForm('school-create-class', async () => {
    const result = await post('/classrooms', {
      name: $('school-class-name').value,
      section: $('school-class-section').value,
      academicYear: $('school-class-year').value
    });
    $('school-create-class').reset();
    await reloadCatalog(result.id); notice(result.message);
    $('school-create-class-details').open = false;
  });

  submitForm('school-create-subject', async () => {
    const classroom = selectedClass();
    if (!classroom) throw new Error('Select a classroom above first');
    const facultyName = $('school-new-faculty').value.trim();
    if (!facultyName) throw new Error('Enter faculty name');
    // Two existing API calls; do not alter subject or attendance database semantics.
    const created = await post('/subjects', { code: $('school-code').value, title: $('school-title').value });
    try {
      await post(`/classrooms/${classroom}/subjects`, { subjectId: created.id, facultyName });
    } catch (error) {
      throw new Error(`Subject created but assignment failed: ${error.message}. Use 'Assign existing subject' below.`);
    }
    $('school-create-subject').reset();
    await reloadCatalog(classroom);
    notice('Subject created and assigned to the selected classroom');
  });

  submitForm('school-assign-subject', async () => {
    if (!selectedClass()) throw new Error('Create or select a classroom first');
    if (!$('school-subject-catalog').value) throw new Error('Choose a subject');
    const result = await post(`/classrooms/${selectedClass()}/subjects`, {
      subjectId: $('school-subject-catalog').value,
      facultyName: $('school-faculty').value
    });
    await reloadClass(); notice(result.message);
  });

  submitForm('school-add-student', async () => {
    if (!selectedClass()) throw new Error('Select a classroom first');
    if (!$('school-student-catalog').value) throw new Error('Select a registered student');
    const result = await post(`/classrooms/${selectedClass()}/students`, {
      studentId: $('school-student-catalog').value
    });
    await reloadClass(); notice(result.message);
  });

  submitForm('school-start-session', async () => {
    if (!selectedClass()) throw new Error('Select a classroom first');
    if (!$('school-session-subject').value) throw new Error('Assign a subject to this classroom first');
    const created = await post(`/classrooms/${selectedClass()}/sessions`, {
      subjectId: $('school-session-subject').value, date: $('school-date').value,
      period: $('school-period').value
    });
    await reloadClass(created.id);
    notice('Session ready. Camera is linked to this class session.');
    showTab('attendance');
    linkSelectedSession(true);
  });

  $('school-classroom').addEventListener('change', () => run(null, reloadClass));
  $('school-session').addEventListener('change', updateSession);
  $('school-reload-students').addEventListener('click', () => run($('school-reload-students'), async () => {
    await reloadStudents(); notice('Student list refreshed');
  }));
  function linkSelectedSession(scrollToCamera) {
    const session = selectedSession();
    if (!session || session.status !== 'OPEN') {
      notice('Select an OPEN classroom session first', true);
      return;
    }
    const field = $('session');
    field.value = session.session_key;
    field.readOnly = true;
    field.title = `Linked to ${session.subject_code} · ${session.session_date} · Period ${session.period_number}`;
    notice(`Ready: ${session.subject_code} · Period ${session.period_number}. Use camera and verify each student.`);
    if (scrollToCamera) field.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }
  $('school-use').addEventListener('click', () => linkSelectedSession(true));
  $('school-manual').addEventListener('click', () => {
    const field = $('session'); field.readOnly = false; field.value = '';
    field.title = ''; notice('Legacy manual session enabled; enter a unique session identifier above.');
  });
  $('school-close').addEventListener('click', () => {
    const s = selectedSession(); if (!s || s.status !== 'OPEN') return;
    if (!confirm(`Close ${s.subject_code} Period ${s.period_number}? New marks will be rejected.`)) return;
    run($('school-close'), async () => {
      const result = await post(`/sessions/${s.id}/close`, {});
      if ($('session').value === s.session_key) { $('session').value = ''; $('session').readOnly = false; }
      await reloadClass(s.id); notice(result.message);
    });
  });
  $('school-export').addEventListener('click', e => {
    if (!selectedSession()) { e.preventDefault(); notice('Select a session first', true); }
  });

  // Original app.js replaces attendance rows after a mark, correction or refresh.
  // Observe only its existing table; do not change app.js or webcam event handlers.
  const originalRows = $('records');
  if (originalRows) new MutationObserver(scheduleReport).observe(originalRows, { childList: true });
  $('school-date').value = localDate();
  showTab('attendance');
  // Real-world first-use guidance: keep setup out of daily attendance view.
  root.querySelector('.school-tab[data-school-tab="attendance"]').title = 'Daily attendance workflow';
  run(null, async () => { await Promise.all([reloadStudents(), reloadCatalog()]); });
})();
