/* FaceAttend Teacher Portal – presentation only. Does not modify authentication or data. */
(() => {
  'use strict';
  function enhance() {
    const card = document.querySelector('main .form-card');
    if (!card || card.dataset.teacherEnhanced === 'true') return;
    const form = card.querySelector('form');
    if (!form || form.querySelector('input[name="loginRole"]')?.value !== 'FACULTY') return;
    const back = card.querySelector('.back');
    const eyebrow = card.querySelector('.eyebrow');
    const heading = card.querySelector('h1');
    const description = card.querySelector('h1 + p');
    const feedback = card.querySelector('.feedback');
    if (![back, eyebrow, heading, description, feedback].every(Boolean)) return;

    document.body.classList.add('teacher-premium');
    card.dataset.teacherEnhanced = 'true';
    card.setAttribute('aria-label', 'Teacher secure sign in');

    const intro = document.createElement('div');
    intro.className = 'teacher-intro';
    const divider = document.createElement('div');
    divider.className = 'teacher-divider';
    divider.setAttribute('aria-hidden','true');
    const login = document.createElement('div');
    login.className = 'teacher-login';

    back.classList.add('teacher-back');
    eyebrow.classList.add('teacher-eyebrow');
    heading.innerHTML = 'Teacher <span>Sign In</span>';
    heading.classList.add('teacher-heading');
    description.classList.add('teacher-description');
    const underline = document.createElement('div');
    underline.className = 'teacher-underline';
    underline.setAttribute('aria-hidden','true');

    const features = document.createElement('div');
    features.className = 'teacher-features';
    features.setAttribute('aria-label', 'Teacher portal features');
    const featureData = [
      ['face', 'Face Recognition'],
      ['calendar', 'Attendance Management'],
      ['chart', 'Class Reports']
    ];
    const featureIcons = {
      face: '<rect x="3" y="3" width="18" height="18" rx="5" stroke-dasharray="5 3"/><circle cx="9" cy="10" r="1"/><circle cx="15" cy="10" r="1"/><path d="M8 15c2.5 2.5 5.5 2.5 8 0"/>',
      calendar: '<rect x="4" y="5" width="16" height="16" rx="2"/><path d="M8 3v4M16 3v4M4 10h16m-12 5 3 3 5-5"/>',
      chart: '<path d="M5 20V12m7 8V6m7 14V3" stroke-width="3" stroke-linecap="round"/>'
    };
    for (const [key, label] of featureData) {
      const tile = document.createElement('div');
      tile.className = 'teacher-feature';
      tile.innerHTML = `<svg viewBox="0 0 24 24" width="27" height="27" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round">${featureIcons[key]}</svg><span>${label}</span>`;
      features.append(tile);
    }
    const decor = document.createElement('div');
    decor.className = 'teacher-decor';
    decor.setAttribute('aria-hidden','true');
    // Deliberately simple artwork; no externally loaded images or animation libraries.
    decor.innerHTML = `<svg viewBox="0 0 440 106" preserveAspectRatio="xMidYMax meet" fill="none" xmlns="http://www.w3.org/2000/svg"><defs><linearGradient id="book-a" x1="0" x2="1"><stop stop-color="#005E51"/><stop offset="1" stop-color="#088A74"/></linearGradient><linearGradient id="book-b" x1="0" x2="1"><stop stop-color="#E4B24D"/><stop offset="1" stop-color="#F9D88D"/></linearGradient></defs><ellipse cx="228" cy="99" rx="199" ry="5" fill="#03483A" opacity=".13"/><path d="M26 97c-11-16-12-35-8-53m8 53c13-22 13-38 14-59m-14 59C8 85 7 75 6 66M26 97c-7-30 3-50 8-64" stroke="#0D806B" stroke-width="3" stroke-linecap="round"/><path d="M19 59C2 54 1 43 8 39c12 1 16 10 11 20Zm15-13c-4-15 3-22 15-24 4 16-4 23-15 24ZM12 78c-12-1-16-11-11-17 11-1 17 5 11 17Zm22-6c0-12 8-18 19-18 0 15-8 21-19 18Z" fill="#388E70" opacity=".9"/><path d="M7 95h36l-5 10H13l-6-10Z" fill="#E6B95D"/><rect x="153" y="73" width="121" height="16" rx="3" fill="url(#book-a)"/><path d="M167 74h107v11H167Z" fill="#FFF4D9"/><rect x="169" y="52" width="118" height="18" rx="3" fill="url(#book-b)"/><path d="M184 53h103v12H184Z" fill="#FFF5DF"/><rect x="155" y="89" width="138" height="14" rx="3" fill="#0B5F52"/><path d="M166 90h127v9H166Z" fill="#FFE9B9"/><path d="M324 52h53l-7 51h-40l-6-51Z" fill="#D5A14F"/><path d="M340 57V12M351 58l11-46M363 58l-7-43" stroke="#015D50" stroke-width="5" stroke-linecap="round"/><path d="M340 13h6m16-1 5 1m-9 3h5" stroke="#F6C867" stroke-width="5" stroke-linecap="round"/><rect x="320" y="48" width="61" height="6" rx="3" fill="#F2D085"/></svg>`;

    intro.append(back, eyebrow, underline, heading, description, features, decor);
    login.append(feedback, form);
    card.replaceChildren(intro, divider, login);

    const username = form.querySelector('#username');
    const password = form.querySelector('#password');
    const show = form.querySelector('#show');
    const submit = form.querySelector('button[type="submit"]');
    if (username) username.setAttribute('aria-label','Teacher username');
    if (password) password.setAttribute('aria-label','Teacher password');
    if (show) {
      show.setAttribute('aria-pressed','false');
      show.addEventListener('click', () => show.setAttribute('aria-pressed', String(password?.type === 'text')));
    }
    if (submit) submit.setAttribute('aria-label','Sign in securely as teacher');

    // The existing form action, CSRF hidden field, and Spring Security handlers are unchanged.
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', enhance, {once:true});
  else enhance();
})();
