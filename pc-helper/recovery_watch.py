import sys, time
sys.path.insert(0, '.')
import zphone
cfg = zphone.load_config()
stable_needed = 10          # 10 polls x 30s = 5 min of menu/ok
stable = 0
last_state = None
end = time.time() + (int(sys.argv[1]) if len(sys.argv) > 1 else 560)
while time.time() < end:
    try:
        st = zphone.request(cfg['host'], cfg['token'], '/status', timeout=8)
        page, age = st.get('page', ''), st.get('page_age_s', '')
    except Exception as e:
        page, age = 'ERR:' + type(e).__name__, ''
    if page != last_state:
        print(time.strftime('%H:%M:%S'), page, 'age=' + str(age), flush=True)
        last_state = page
    if page in ('menu', 'ok'):
        stable += 1
        if stable >= stable_needed:
            print('STABLE-5MIN-ACHIEVED', time.strftime('%H:%M:%S'), page, flush=True)
            sys.exit(0)
    else:
        stable = 0
    time.sleep(30)
print('WINDOW-ENDED stable_polls=' + str(stable), flush=True)
sys.exit(2)
