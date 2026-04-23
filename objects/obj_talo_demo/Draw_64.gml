/// @description Отрисовка GUI
var gw = display_get_gui_width();
var gh = display_get_gui_height();

draw_set_alpha(1);
draw_set_color(make_color_rgb(28, 32, 40));
draw_rectangle(0, 0, gw, gh, false);

var pad = 16;
var log_h = min(520, gh * 0.42);

draw_set_color(make_color_rgb(45, 52, 64));
draw_rectangle(pad, pad, gw - pad, log_h, false);

draw_set_color(c_white);
draw_set_font(-1);
draw_set_halign(fa_left);
draw_set_valign(fa_top);

draw_text(pad + 8, pad + 6, "TaloSDK — демо (Async Social id=" + string(TaloSDK_ASYNC_EVENT_ID) + ", talo_op в логе)");

var y0 = pad + 36 - scroll_log;
draw_set_color(make_color_rgb(210, 218, 230));
for (var li = 0; li < array_length(log_lines); li++) {
	var ty = y0 + li * 22;
	if (ty > log_h - 8) break;
	if (ty < pad + 32) continue;
	draw_text_ext(pad + 10, ty, log_lines[li], -1, gw - pad * 2 - 20);
}

var cols = 2;
var btn_w = (gw - pad * 3) / cols;
var btn_h = 56;
var btn_top = log_h + pad * 2;

draw_set_halign(fa_center);
draw_set_valign(fa_middle);
var mxc = device_mouse_x_to_gui(0);
var myc = device_mouse_y_to_gui(0);
for (var i = 0; i < array_length(btn_labels); i++) {
	var col = i mod cols;
	var row = i div cols;
	var bx = pad + col * (btn_w + pad);
	var by = btn_top + row * (btn_h + 10);
	var hot = point_in_rectangle(mxc, myc, bx, by, bx + btn_w, by + btn_h);
	draw_set_color(hot ? make_color_rgb(70, 120, 200) : make_color_rgb(55, 62, 78));
	draw_rectangle(bx, by, bx + btn_w, by + btn_h, false);
	draw_set_color(c_white);
	draw_text(bx + btn_w * 0.5, by + btn_h * 0.5, btn_labels[i]);
}

draw_set_halign(fa_left);
draw_set_valign(fa_top);
draw_set_color(make_color_rgb(160, 168, 180));
draw_text(pad, gh - 52, "Колёсико мыши — прокрутка лога. Ответы REST приходят в Other > Async Social (см. Other_70).");
draw_text(pad, gh - 30, "Тестовый API: options/extensions/TaloSDK.json (api_endpoint, api_key).");
