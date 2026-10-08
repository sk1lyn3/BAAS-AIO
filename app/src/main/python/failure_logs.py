"""Keep bounded workflow context without repeated polling crowding it out."""


def append_workflow_log(logs, entry):
    message = str(entry.get("message", "")).strip()
    if not (1 <= entry.get("level", 0) < 3) or not message or message.startswith(("Traceback", "File ")):
        return
    if logs and logs[-1]["message"] == message and logs[-1]["level"] == entry["level"]:
        logs[-1]["count"] = logs[-1].get("count", 1) + 1
        logs[-1]["lastTime"] = entry.get("time")
    else:
        logs.append(dict(entry, message=message))
        del logs[:-200]
