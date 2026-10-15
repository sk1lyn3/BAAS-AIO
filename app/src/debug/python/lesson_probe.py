"""Real-game lesson lookup/navigation probe: opens info, never starts a lesson."""
import json
from pathlib import Path
import shutil
import time


def run(files_dir, bridge, native, gate, cycles=0, task="lesson_probe", profile_path=None):
    from core_loader import prepare
    prepare(files_dir)
    from platform_bindings import install
    install()
    from core.Baas_thread import func_dict
    from core.lesson_students import detect_students, resolve_student
    from core import image
    from module import lesson
    import core_task

    source = Path(profile_path)
    root = Path(files_dir) / "lesson-probe"
    profile = root / "profile"
    profile.mkdir(parents=True, exist_ok=True)
    for name in ("config.json", "event.json", "switch.json"):
        if (source / name).exists():
            shutil.copyfile(source / name, profile / name)
    settings_path = Path(files_dir) / 'lesson-probe.json'
    settings = json.loads(settings_path.read_text()) if settings_path.exists() else {}
    values = json.loads((profile / 'config.json').read_text())
    students = settings.get('students', values['lesson_favorStudent'])
    target_ids = {resolve_student(name) for name in students}
    if not target_ids or None in target_ids:
        raise ValueError('Lesson probe needs a nonempty list of known students')
    values.update(lesson_favorStudent=students, purchase_lesson_ticket_times='0',
                  push_after_error=False, push_after_completion=False)
    (profile / 'config.json').write_text(json.dumps(values, ensure_ascii=False))
    evidence = {'dryRun': True, 'students': students, 'visits': []}
    current_region = None
    deadline = time.monotonic() + 240

    class ProbeGate:
        def __getattr__(self, name):
            return getattr(gate, name)

        def checkpoint(self, phase):
            if time.monotonic() > deadline:
                raise TimeoutError('Lesson navigation probe exceeded 4 minutes')
            return gate.checkpoint(phase)

    original_task = func_dict['lesson']
    original_execute = lesson.execute_lesson
    original_region = lesson.get_lesson_region_num

    def read_region(engine):
        nonlocal current_region
        result = original_region(engine)
        if isinstance(result, int):
            current_region = result
        return result

    def open_info_only(engine, block):
        import cv2
        engine.update_screenshot_array()
        frame = image.resize_ss_image(engine, (0, 0, 1280, 720))
        detected = detect_students(frame, lesson.get_favor_student_detect_region(engine, block), 3)
        if not target_ids.intersection(detected):
            raise RuntimeError('Selected lesson no longer contains a configured student')
        lesson.to_location_info(engine, block)
        engine.update_screenshot_array()
        screenshot = root / ('visit-%02d.png' % (len(evidence['visits']) + 1))
        cv2.imwrite(str(screenshot), engine.latest_img_array)
        evidence['visits'].append({'region': current_region, 'block': block,
                                   'detectedStudents': detected, 'screenshot': str(screenshot)})
        engine.logger.info('Lesson probe opened region=%s block=%s; NO ticket used' %
                           (current_region, block + 1))
        lesson.to_all_locations(engine, True)
        return 'lesson_report'  # Simulate accounting, never press the start button.

    def probe(engine):
        engine.to_main_page()
        lesson.prepare_lesson_regions(engine)
        lesson.to_lesson_location_select(engine, True)
        evidence['realTickets'] = lesson.get_lesson_tickets(engine)
        engine.lesson_tickets = max(1, min(int(cycles or 27), 27))
        lesson.invite_favor_student(engine)
        if not evidence['visits']:
            raise RuntimeError('No configured student found; navigation is not verified')
        return True

    try:
        lesson.execute_lesson = open_info_only
        lesson.get_lesson_region_num = read_region
        func_dict['lesson'] = probe
        result = json.loads(core_task.run(files_dir, bridge, native, ProbeGate(),
                                         task='core_task', profile_path=str(profile), manual_task='lesson'))
        result['lessonProbe'] = evidence
        return json.dumps(result, ensure_ascii=False)
    finally:
        func_dict['lesson'] = original_task
        lesson.execute_lesson = original_execute
        lesson.get_lesson_region_num = original_region
