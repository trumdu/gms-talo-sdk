/// @description Демо TaloSDK — сценарии как в extensions/TaloSDK/tools/test_routes.mjs (OpenAPI v1)
randomize();

leaderboard_internal_name = "demo";

demo_channel_id = -1;
demo_save_id = -1;
demo_feed_cat_internal = "";
demo_stat_internal = "";
demo_auth_ident = "";
demo_auth_pass = "";

btn_labels = [
	"GET /v1/game-config",
	"GET /v1/game-stats",
	"GET feedback categories",
	"GET /v1/game-channels",
	"POST game-channels (create)",
	"GET game-channels/:id",
	"GET game-channels/subscriptions",
	"POST game-channels/:id/join",
	"GET game-channels/:id/members",
	"PUT game-channels/:id/storage",
	"GET game-channels/:id/storage",
	"GET game-channels/:id/storage/list",
	"PUT game-channels/:id (rename)",
	"DELETE game-channels/:id",
	"GET leaderboard entries",
	"POST leaderboard entry (score)",
	"leaderboard +5 (add_score)",
	"POST /v1/events",
	"GET /v1/game-saves",
	"POST game-saves",
	"PATCH game-saves/:id",
	"DELETE game-saves/:id",
	"GET player-stats",
	"identify new anonymous",
	"GET /v1/players/search (self id)",
	"GET /v1/players/:id (self)",
	"PATCH /v1/players/:id (demo prop)",
	"POST /v1/players/socket-token",
	"POST /v1/socket-tickets",
	"GET /v1/players/presence/:id",
	"PUT /v1/players/presence",
	"GET /v1/player-groups/:id (probe)",
	"GET relationships/subscribers",
	"GET relationships/subscriptions",
	"POST relationships (aliasId=1, probe)",
	"POST feedback (if category cached)",
	"GET game-stats/:name (if cached)",
	"POST auth/register (rnd ident)",
	"POST auth/login (last rnd)",
	"POST players/merge (probe uuid2)",
];

log_lines = [];
scroll_log = 0;
btn_scroll = 0;

/// Панорамирование логом / сеткой: мышь и первый палец (device 0 — сенсор + десктоп)
scroll_pan_dev = 0;
scroll_pan_zone = "none";
scroll_pan_press_x = 0;
scroll_pan_press_y = 0;
scroll_pan_moved = false;
scroll_pan_log_y0 = 0;
scroll_pan_log_scroll0 = 0;
scroll_pan_btn_y0 = 0;
scroll_pan_btn_scroll0 = 0;
scroll_pan_threshold = 10;

function talo_demo_log(_msg) {
	var line = string(_msg);
	array_push(log_lines, line);
	var cap = 160;
	if (array_length(log_lines) > cap) {
		array_delete(log_lines, 0, array_length(log_lines) - cap);
	}
}

function talo_demo_data_root(_j) {
	if (!is_struct(_j)) return _j;
	if (variable_struct_exists(_j, "data")) return _j.data;
	return _j;
}

function talo_demo_capture_from_response(_op, _ok, _code, _body) {
	if (!_ok || _code < 200 || _code >= 300) return;
	var j = undefined;
	try {
		j = json_parse(_body);
	} catch (_e) {
		return;
	}
	if (!is_struct(j)) return;
	var d = talo_demo_data_root(j);
	switch (_op) {
		case "game_channels_create":
			if (is_struct(d) && variable_struct_exists(d, "channel")) {
				var ch = d.channel;
				if (is_struct(ch) && variable_struct_exists(ch, "id")) {
					demo_channel_id = real(ch.id);
					talo_demo_log("(demo) сохранён channel id для кнопок: " + string(demo_channel_id));
				}
			}
			break;
		case "game_saves_create":
			if (is_struct(d) && variable_struct_exists(d, "save")) {
				var sv = d.save;
				if (is_struct(sv) && variable_struct_exists(sv, "id")) {
					demo_save_id = real(sv.id);
					talo_demo_log("(demo) сохранён save id: " + string(demo_save_id));
				}
			}
			break;
		case "game_feedback_categories_get":
			var arr = undefined;
			if (is_struct(d) && variable_struct_exists(d, "feedbackCategories")) arr = d.feedbackCategories;
			else if (variable_struct_exists(j, "feedbackCategories")) arr = j.feedbackCategories;
			if (is_array(arr) && array_length(arr) > 0) {
				var e = arr[0];
				if (is_struct(e) && variable_struct_exists(e, "internalName")) {
					demo_feed_cat_internal = string(e.internalName);
					talo_demo_log("(demo) первая категория feedback: \"" + demo_feed_cat_internal + "\"");
				}
			}
			break;
		case "game_stats_list":
			var stats = undefined;
			if (is_struct(d) && variable_struct_exists(d, "stats")) stats = d.stats;
			else if (variable_struct_exists(j, "stats")) stats = j.stats;
			if (is_array(stats) && array_length(stats) > 0) {
				var s0 = stats[0];
				if (is_struct(s0) && variable_struct_exists(s0, "internalName")) {
					demo_stat_internal = string(s0.internalName);
					talo_demo_log("(demo) первый stat internalName: \"" + demo_stat_internal + "\"");
				}
			}
			break;
		default:
			break;
	}
}

function talo_demo_run_action(_idx) {
	switch (_idx) {
		case 0: TaloSDK_game_config_get(); break;
		case 1: TaloSDK_game_stats_list(); break;
		case 2: TaloSDK_game_feedback_categories_get(); break;
		case 3: TaloSDK_game_channels_list("page=0"); break;
		case 4: {
			var b = {
				name: "gm-demo-" + string(get_timer()),
				props: [{ key: "t", value: "1" }],
				private: false,
				autoCleanup: false,
			};
			TaloSDK_game_channels_create(json_stringify(b));
			break;
		}
		case 5:
			if (demo_channel_id < 0) talo_demo_log("Сначала создайте канал (POST game-channels).");
			else TaloSDK_game_channels_get(demo_channel_id);
			break;
		case 6: TaloSDK_game_channels_subscriptions(""); break;
		case 7:
			if (demo_channel_id < 0) talo_demo_log("Нет channel id.");
			else TaloSDK_game_channels_join(demo_channel_id);
			break;
		case 8:
			if (demo_channel_id < 0) talo_demo_log("Нет channel id.");
			else TaloSDK_game_channels_members(demo_channel_id, "page=0");
			break;
		case 9:
			if (demo_channel_id < 0) talo_demo_log("Нет channel id.");
			else TaloSDK_game_channels_storage_put(demo_channel_id, json_stringify({ props: [{ key: "demo", value: "gm" }] }));
			break;
		case 10:
			if (demo_channel_id < 0) talo_demo_log("Нет channel id.");
			else TaloSDK_game_channels_storage_get(demo_channel_id, "propKey=demo");
			break;
		case 11:
			if (demo_channel_id < 0) talo_demo_log("Нет channel id.");
			else TaloSDK_game_channels_storage_list(demo_channel_id, "propKeys=demo");
			break;
		case 12:
			if (demo_channel_id < 0) talo_demo_log("Нет channel id.");
			else TaloSDK_game_channels_update(demo_channel_id, json_stringify({ name: "gm-renamed-" + string(get_timer()) }));
			break;
		case 13:
			if (demo_channel_id < 0) talo_demo_log("Нет channel id.");
			else {
				TaloSDK_game_channels_delete(demo_channel_id);
				demo_channel_id = -1;
			}
			break;
		case 14: TaloSDK_leaderboard_entries_get(leaderboard_internal_name, "page=0"); break;
		case 15:
			TaloSDK_leaderboard_entries_post(leaderboard_internal_name, json_stringify({ score: irandom(999999) }));
			break;
		case 16: TaloSDK_leaderboard_entries_add_score(leaderboard_internal_name, 5); break;
		case 17: {
			var evjson = "[{\"name\":\"talo_gm_demo\",\"props\":[{\"key\":\"client\",\"value\":\"GameMaker\"}]}]";
			TaloSDK_events_track(evjson);
			break;
		}
		case 18: TaloSDK_game_saves_list(); break;
		case 19: {
			var payload = {
				name: "gm-save-" + string(get_timer()),
				content: { objects: [] },
			};
			TaloSDK_game_saves_create(json_stringify(payload));
			break;
		}
		case 20:
			if (demo_save_id < 0) talo_demo_log("Сначала POST game-saves (создать сохранение).");
			else TaloSDK_game_saves_update(demo_save_id, json_stringify({ name: "gm-save-upd", content: { objects: [] } }));
			break;
		case 21:
			if (demo_save_id < 0) talo_demo_log("Нет save id.");
			else {
				TaloSDK_game_saves_delete(demo_save_id);
				demo_save_id = -1;
			}
			break;
		case 22: TaloSDK_game_stats_player_stats(); break;
		case 23: {
			var nid = "gm_" + string(get_timer()) + "_" + string(irandom(99999999));
			TaloSDK_players_identify("anonymous", nid);
			talo_demo_log("identify anonymous → " + nid);
			break;
		}
		case 24: {
			var pid = TaloSDK_get_last_player_id();
			if (pid == "") talo_demo_log("Нет player id — дождитесь identify.");
			else TaloSDK_players_search(pid);
			break;
		}
		case 25: {
			var pid = TaloSDK_get_last_player_id();
			if (pid == "") talo_demo_log("Нет player id.");
			else TaloSDK_players_get(pid);
			break;
		}
		case 26: {
			var pid = TaloSDK_get_last_player_id();
			if (pid == "") talo_demo_log("Нет player id.");
			else {
				var props = json_stringify([{ key: "gm_demo_prop", value: string(get_timer()) }]);
				TaloSDK_players_patch_props(pid, props);
			}
			break;
		}
		case 27: TaloSDK_players_create_socket_token(); break;
		case 28: TaloSDK_socket_tickets_create(); break;
		case 29: {
			var pid = TaloSDK_get_last_player_id();
			if (pid == "") talo_demo_log("Нет player id.");
			else TaloSDK_players_presence_get(pid);
			break;
		}
		case 30:
			TaloSDK_players_presence_put(json_stringify({ online: true, customStatus: "gm-demo" }));
			break;
		case 31:
			TaloSDK_player_groups_get("00000000-0000-4000-8000-000000000001", "membersPage=0");
			break;
		case 32: TaloSDK_players_relationships_subscribers("page=0"); break;
		case 33: TaloSDK_players_relationships_subscriptions("page=0"); break;
		case 34:
			TaloSDK_players_relationships_create(json_stringify({ aliasId: 1, relationshipType: "unidirectional" }));
			break;
		case 35:
			if (demo_feed_cat_internal == "") talo_demo_log("Сначала GET feedback categories (кэшируется internalName).");
			else TaloSDK_game_feedback_create(demo_feed_cat_internal, json_stringify({ comment: "gm demo " + string(get_timer()) }));
			break;
		case 36:
			if (demo_stat_internal == "") talo_demo_log("Сначала GET /v1/game-stats (кэшируется internalName).");
			else TaloSDK_game_stats_get(demo_stat_internal);
			break;
		case 37: {
			demo_auth_ident = "gm_rt_" + string(get_timer()) + "_" + string(irandom(99999));
			demo_auth_pass = "GmDemo1_" + string(irandom(99999999));
			TaloSDK_players_auth_register(json_stringify({
				identifier: demo_auth_ident,
				password: demo_auth_pass,
				verificationEnabled: false,
				withRefresh: true,
			}));
			talo_demo_log("auth register → " + demo_auth_ident + " (пароль в сессии объекта, только для теста)");
			break;
		}
		case 38:
			if (demo_auth_ident == "" || demo_auth_pass == "") talo_demo_log("Сначала POST auth/register (rnd).");
			else TaloSDK_players_auth_login(json_stringify({ identifier: demo_auth_ident, password: demo_auth_pass, withRefresh: true }));
			break;
		case 39: {
			var p1 = TaloSDK_get_last_player_id();
			if (p1 == "") talo_demo_log("Нет player id для merge.");
			else TaloSDK_players_merge(p1, "00000000-0000-4000-8000-000000000002");
			break;
		}
		default: break;
	}
}

TaloSDK_init();
talo_demo_log("TaloSDK_init() — API: options/extensions/TaloSDK.json");
talo_demo_log("Лидерборд: \"" + leaderboard_internal_name + "\" (как TALO_TEST_LEADERBOARD в test_routes.mjs).");
talo_demo_log("Полный прогон 58 маршрутов: node extensions/TaloSDK/tools/test_routes.mjs");
if (os_type != os_android) {
	talo_demo_log("Нативный TaloSDK ориентирован на Android.");
}
