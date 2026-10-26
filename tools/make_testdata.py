#!/usr/bin/env python3
"""Генератор тестового набора данных для Напоминатора.

Результат: JSON в формате резервной копии приложения («Ещё → Импорт из JSON»).
Все даты считаются от текущего момента, поэтому набор всегда «свежий»:
история за последние дни, сегодняшние задачи в разных состояниях, будущие срабатывания.

    python3 tools/make_testdata.py                       # testdata/testdata.json, часовой пояс системы
    python3 tools/make_testdata.py --tz Asia/Novosibirsk --days 90 -o /tmp/data.json

Правила расписаний повторяют app/.../data/Schedule.kt; тест ScheduleCompatTest сверяет их.
"""
import argparse
import calendar
import datetime as dt
import json
import random
from zoneinfo import ZoneInfo

EPOCH = dt.date(1970, 1, 1)


def ed(d):
    return (d - EPOCH).days


def minutes_on(s, d):
    """Те же правила, что Schedule.minutesOn в приложении."""
    e = ed(d)
    t = s["type"]
    if t != "ONCE" and e < s["startEpochDay"]:
        return []
    if s.get("untilEpochDay") is not None and e > s["untilEpochDay"]:
        return []
    n = max(s["every"], 1)
    start = EPOCH + dt.timedelta(days=s["startEpochDay"])
    if t == "ONCE":
        ok = e == s["onceEpochDay"]
    elif t == "DAYS_OF_WEEK":
        ok = d.isoweekday() in s["daysOfWeek"]
    elif t == "EVERY_N_DAYS":
        ok = (e - s["startEpochDay"]) % n == 0
    elif t == "EVERY_N_WEEKS":
        monday = ed(start - dt.timedelta(days=start.isoweekday() - 1))
        ok = d.isoweekday() in s["daysOfWeek"] and ((e - monday) // 7) % n == 0
    elif t == "EVERY_N_MONTHS":
        months = (d.year - start.year) * 12 + d.month - start.month
        ok = months % n == 0 and d.day == min(start.day, calendar.monthrange(d.year, d.month)[1])
    elif t == "MONTH_DAYS":
        last = calendar.monthrange(d.year, d.month)[1]
        ok = any((x == -1 and d.day == last) or x == d.day for x in s["monthDays"])
    elif t == "MONTH_NTH_WEEKDAY":
        last = calendar.monthrange(d.year, d.month)[1]
        ok = d.isoweekday() == s["nthDow"] and ((d.day + 7 > last) if s["nth"] == -1 else (d.day - 1) // 7 + 1 == s["nth"])
    elif t == "INTERVAL":
        step = max(s["intervalMinutes"], 1)
        out, m = [], s["windowStart"]
        while m <= s["windowEnd"] and m < 1440:
            out.append(m)
            m += step
        return out
    else:
        ok = False
    return sorted(set(s["times"])) if ok else []


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tz", default=None, help="часовой пояс IANA, по умолчанию системный")
    ap.add_argument("--days", type=int, default=180, help="сколько дней истории (по умолчанию ~6 месяцев)")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("-o", "--out", default="testdata/testdata.json")
    a = ap.parse_args()
    random.seed(a.seed)

    tz = ZoneInfo(a.tz) if a.tz else dt.datetime.now().astimezone().tzinfo
    now = dt.datetime.now(tz).replace(second=0, microsecond=0)
    today = now.date()
    now_min = now.hour * 60 + now.minute

    def ms(d, minutes):
        return int(dt.datetime.combine(d, dt.time(0, 0), tz).timestamp() * 1000) + minutes * 60000

    created = ms(today - dt.timedelta(days=a.days), 0)
    start = ed(today - dt.timedelta(days=a.days))

    def sched(**k):
        base = {"type": "DAYS_OF_WEEK", "times": [], "daysOfWeek": [1, 2, 3, 4, 5, 6, 7], "every": 1, "startEpochDay": start,
                "monthDays": [1], "nth": 1, "nthDow": 1, "onceEpochDay": 0, "intervalMinutes": 60, "windowStart": 480,
                "windowEnd": 1320, "untilEpochDay": None}
        base.update(k)
        return base

    def cfg(fields, accumulative=False, goal=None):
        return {"fields": [{"name": n, "unit": u, "min": None, "max": None, "step": st} for n, u, st in fields],
                "accumulative": accumulative, "goal": goal}

    R = []

    def add(**k):
        d = dict(id=len(R) + 1, title="", note="", category="", priority=0, kind="TASK", schedule=None, measure=None,
                 repeatEveryMin=0, repeatMax=0, status="ACTIVE", createdAt=created, tags="", checklist="",
                 triggerMode="TIME", conditions=None, _sched=None, _cfg=None, _conds=None)
        d.update(k)
        R.append(d)
        return d

    tomorrow = today + dt.timedelta(days=1)
    add(title="Зарядка", category="Здоровье", tags="утро", _sched=sched(times=[450]))
    add(title="Таблетки", note="После еды", category="Здоровье", priority=1, repeatEveryMin=10, repeatMax=6, _sched=sched(times=[540, 780, 1200]))
    add(title="Вода", category="Здоровье", kind="MEASURE", _sched=sched(type="INTERVAL", intervalMinutes=180, windowStart=540, windowEnd=1260),
        _cfg=cfg([("Вода", "мл", 250.0)], True, 2000.0))
    add(title="Вес", category="Здоровье", tags="утро", kind="MEASURE", _sched=sched(times=[480]), _cfg=cfg([("Вес", "кг", 0.1)], False, 80.0))
    add(title="Давление", category="Здоровье", priority=1, kind="MEASURE", repeatEveryMin=15, repeatMax=3, _sched=sched(times=[600, 1260]),
        _cfg=cfg([("Верхнее", "мм рт. ст.", 1.0), ("Нижнее", "мм рт. ст.", 1.0), ("Пульс", "уд/мин", 1.0)]))
    add(title="Чтение 20 минут", category="Личное", tags="вечер", checklist="Книга\nЗаметки", _sched=sched(times=[1260]))
    add(title="Позвонить маме", category="Личное", _sched=sched(daysOfWeek=[7], times=[1020]))
    add(title="Платёж за квартиру", category="Дом", priority=2, _sched=sched(type="MONTH_DAYS", monthDays=[25], times=[600]))
    add(title="Полить цветы", category="Дом", triggerMode="CONDITION", _sched=sched(),
        _conds=[{"kind": "WIFI", "enter": True, "lat": 0.0, "lon": 0.0, "radiusM": 150, "ssid": "Дом-5G", "btAddress": "", "btName": "", "minIntervalMin": 720, "once": False}])
    add(title="Убрать мусор", category="Дом", triggerMode="BOTH", _sched=sched(daysOfWeek=[1, 3, 5], windowStart=1080, windowEnd=1320),
        _conds=[{"kind": "PLACE", "enter": True, "lat": 55.0302, "lon": 82.9204, "radiusM": 200, "ssid": "", "btAddress": "", "btName": "", "minIntervalMin": 60, "once": False}])
    add(title="Старая привычка", category="Личное", status="ARCHIVED", _sched=sched(times=[600]))
    add(title="Подстричь ногти", category="Личное", _sched=sched(type="EVERY_N_WEEKS", every=2, daysOfWeek=[6], times=[660]))
    add(title="Квартальный отчёт", category="Работа", priority=1, _sched=sched(type="MONTH_NTH_WEEKDAY", nth=-1, nthDow=5, times=[960]))
    add(title="Встреча с врачом", note="Взять полис", category="Здоровье", priority=2, _sched=sched(type="ONCE", onceEpochDay=ed(tomorrow), startEpochDay=ed(tomorrow), times=[840]))
    add(title="Проверить фильтр", category="Дом", status="PAUSED", _sched=sched(type="EVERY_N_DAYS", every=3, times=[1140]))
    add(title="Записать пробег", category="Работа", triggerMode="CONDITION", _sched=sched(),
        _conds=[{"kind": "BLUETOOTH", "enter": True, "lat": 0.0, "lon": 0.0, "radiusM": 150, "ssid": "", "btAddress": "AA:BB:CC:DD:EE:FF", "btName": "Авто", "minIntervalMin": 240, "once": False}])
    add(title="Шаги", category="Здоровье", kind="MEASURE", _sched=sched(times=[1320]), _cfg=cfg([("Шаги", "шагов", 1000.0)], True, 8000.0))
    add(title="Проверка раз в 3 месяца", category="Дом", _sched=sched(type="EVERY_N_MONTHS", every=3, times=[600],
                                                                      startEpochDay=ed(today - dt.timedelta(days=100)), untilEpochDay=ed(today + dt.timedelta(days=365))))

    for r in R:
        r["schedule"] = json.dumps(r.pop("_sched"), ensure_ascii=False)
        c = r.pop("_cfg")
        r["measure"] = json.dumps(c, ensure_ascii=False) if c else None
        cs = r.pop("_conds")
        r["conditions"] = json.dumps(cs, ensure_ascii=False) if cs else None

    by_title = {r["title"]: r for r in R}
    occ, meas = [], []
    oid = mid = 0

    def add_occ(r, due, d, state, checked=""):
        nonlocal oid
        oid += 1
        occ.append(dict(id=oid, reminderId=r["id"], dueAt=due, epochDay=ed(d), state=state,
                        resolvedAt=None if state == "PENDING" else due + 300000, comment=None, checked=checked))

    reliability = {"Зарядка": 0.92, "Вес": 0.93, "Давление": 0.85, "Таблетки": 0.8, "Чтение 20 минут": 0.7}
    for r in R:
        if r["status"] != "ACTIVE" or r["triggerMode"] != "TIME":
            continue
        s = json.loads(r["schedule"])
        p = reliability.get(r["title"], 0.78)
        for k in range(a.days, -1, -1):
            d = today - dt.timedelta(days=k)
            for m in minutes_on(s, d):
                due = ms(d, m)
                if due < r["createdAt"]:
                    continue
                if k == 0:
                    if m > now_min:
                        continue                                    # будущее сегодня остаётся «виртуальным»
                    state = "PENDING" if r["title"] in ("Давление", "Вода") else "DONE"
                elif r["title"] == "Зарядка" and k <= 9:
                    state = "DONE"                                  # серия 9 дней
                else:
                    x = random.random()
                    state = "DONE" if x < p else "MISSED" if x < p + 0.12 else "SKIPPED" if x < p + 0.16 else "SHIFTED"
                add_occ(r, due, d, state, "0" if r["title"] == "Чтение 20 минут" and state == "DONE" and random.random() < .5 else "")

    # условные срабатывания (в пределах окна для «условие + время»)
    for title, minute, days_back in (("Полить цветы", 1170, (1, 3, 4, 8)), ("Убрать мусор", 1110, (0, 2, 4, 7, 9)), ("Записать пробег", 540, (1, 2, 5, 6))):
        r = by_title[title]
        s = json.loads(r["schedule"])
        for k in days_back:
            d = today - dt.timedelta(days=k)
            if r["triggerMode"] == "BOTH" and d.isoweekday() not in s["daysOfWeek"]:
                continue
            if k == 0 and minute > now_min:
                continue
            add_occ(r, ms(d, minute + k), d, "DONE" if k else "PENDING")

    # измерения
    w = 88.0
    for k in range(a.days, -1, -1):
        d = today - dt.timedelta(days=k)
        target = 81.9 + 6.0 * k / a.days                  # плавное снижение к 81,9 кг
        w += 0.15 * (target - w) + random.uniform(-0.3, 0.3)
        if random.random() < 0.9 or k == 0:
            mid += 1
            meas.append(dict(id=mid, reminderId=by_title["Вес"]["id"], ts=ms(d, 480 + random.randint(0, 20)), epochDay=ed(d), field="Вес", value=round(81.9 if k == 0 else w, 1)))
        if k % 2 == 0:
            t = ms(d, 610)
            for f, v in (("Верхнее", random.randint(118, 134)), ("Нижнее", random.randint(74, 86)), ("Пульс", random.randint(62, 78))):
                mid += 1
                meas.append(dict(id=mid, reminderId=by_title["Давление"]["id"], ts=t, epochDay=ed(d), field=f, value=float(v)))
        for j in range(3):
            m = 540 + j * 180 + random.randint(0, 15)
            if k == 0 and m > now_min:
                continue
            mid += 1
            meas.append(dict(id=mid, reminderId=by_title["Вода"]["id"], ts=ms(d, m), epochDay=ed(d), field="Вода", value=float(random.choice([250, 250, 500]))))
        if k > 0:
            mid += 1
            meas.append(dict(id=mid, reminderId=by_title["Шаги"]["id"], ts=ms(d, 1320), epochDay=ed(d), field="Шаги", value=float(random.randint(3500, 11500))))

    sets = [dict(id=1, name="Здоровье", reminderIds="", category="Здоровье"),
            dict(id=2, name="Дом", reminderIds="", category="Дом"),
            dict(id=3, name="Утро", reminderIds=f'{by_title["Зарядка"]["id"]},{by_title["Вес"]["id"]}', category="")]

    out = dict(version=1, exportedAt=int(now.timestamp() * 1000), reminders=R, occurrences=occ, measurements=meas, sets=sets)
    import os
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(f"{a.out}: напоминаний {len(R)}, срабатываний {len(occ)}, измерений {len(meas)}, наборов {len(sets)}; «сейчас» {now:%Y-%m-%d %H:%M} {tz}")


if __name__ == "__main__":
    main()
