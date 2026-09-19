# MAICA MC Handoff —— maidllmlocal 跨前端联动的 MAS 侧交接器
#
# 做什么：把 persistent.mas_player_additions（含 write_memory 蒸馏出的记忆）
# 定期落成 handoff.json，供 maidllmlocal（Minecraft 侧）读取并作为
# 每轮 query 的临时 savefile 注入——「她去没去过 Minecraft」这类事件知识
# 由此跨前端流动。不修改 MAICA Blessland 本体，可独立装卸。
#
# 输出：<basedir>/game/Submods/MAICA_MCHandoff/handoff.json
# 协议见 maidllmlocal/docs/CROSSFRONTEND.md

init -990 python:
    store.mas_submod_utils.Submod(
        author="maidllmlocal",
        name="MAICA MC Handoff",
        description="Exports MAICA memory additions for maidllmlocal cross-frontend handoff.",
        version="0.1.0",
    )

init -10 python:
    import io
    import json
    import os
    import time

    _MAICA_MC_HANDOFF_DIR = os.path.join(
        renpy.config.basedir, "game", "Submods", "MAICA_MCHandoff")
    _MAICA_MC_HANDOFF_PATH = os.path.join(_MAICA_MC_HANDOFF_DIR, "handoff.json")

    # 距上次落盘的最短间隔（秒）；内容没变则无论多久都不写
    _MAICA_MC_HANDOFF_MIN_INTERVAL = 30.0
    _maica_mc_handoff_last_write = [0.0]   # 列表当可变盒子用（py2 兼容）
    _maica_mc_handoff_last_hash = [None]

    def maica_mc_handoff_export(force=False):
        """把当前 additions 快照写成 JSON。内容未变或距上次过近时跳过。"""
        try:
            additions = list(getattr(persistent, "mas_player_additions", None) or [])
            payload = {
                "version": 1,
                "player": store.player,
                "chat_session": persistent.maica_setting_dict.get("chat_session", 1),
                "updated_at": time.time(),
                "mas_player_additions": additions,
            }
            text = json.dumps(payload, ensure_ascii=False, indent=2)
            digest = hash(text)
            now = time.time()
            if not force:
                if digest == _maica_mc_handoff_last_hash[0]:
                    return
                if now - _maica_mc_handoff_last_write[0] < _MAICA_MC_HANDOFF_MIN_INTERVAL:
                    return
            if not os.path.isdir(_MAICA_MC_HANDOFF_DIR):
                os.makedirs(_MAICA_MC_HANDOFF_DIR)
            # 先写临时文件再改名：MC 侧永远读到完整 JSON，不会读到写一半的
            tmp_path = _MAICA_MC_HANDOFF_PATH + ".tmp"
            if isinstance(text, bytes):
                text = text.decode("utf-8")
            with io.open(tmp_path, "w", encoding="utf-8") as fp:
                fp.write(text)
            if os.path.exists(_MAICA_MC_HANDOFF_PATH):
                os.remove(_MAICA_MC_HANDOFF_PATH)
            os.rename(tmp_path, _MAICA_MC_HANDOFF_PATH)
            _maica_mc_handoff_last_write[0] = now
            _maica_mc_handoff_last_hash[0] = digest
            store.mas_submod_utils.submod_log.debug(
                "MAICA MC Handoff: exported {} addition(s)".format(len(additions)))
        except Exception as error:
            store.mas_submod_utils.submod_log.error(
                "MAICA MC Handoff: export failed: {}".format(error))

    # 启动时导一次（覆盖上次会话中 write_memory 的增量）
    @store.mas_submod_utils.functionplugin("ch30_preloop")
    def _maica_mc_handoff_on_start():
        maica_mc_handoff_export(force=True)

    # 主循环里轮询：write_memory 落地后一分钟内自然进交接文件
    @store.mas_submod_utils.functionplugin("ch30_loop")
    def _maica_mc_handoff_on_loop():
        maica_mc_handoff_export()
