/// @description Async Social — ответы TaloSDK (см. docs-main/TALO_API_GUIDE.md §7)
if (async_load[? "id"] != TaloSDK_ASYNC_EVENT_ID) exit;

var ok = async_load[? "success"];
var op = string(async_load[? "talo_op"]);
var code = async_load[? "http_code"];
var body = string(async_load[? "body"]);
var err = string(async_load[? "error"]);
var auto = async_load[? "talo_auto"];

if (string_length(body) > 500) {
	body = string_copy(body, 1, 500) + "...";
}

var head = (ok ? "[OK] " : "[FAIL] ") + op + " http=" + string(code);
if (auto > 0.5) head += " (auto)";
if (!ok && err != "") head += " err=" + err;

talo_demo_log(head);
talo_demo_log(body);
