(function startFurnitureJsonPortal() {
  "use strict";

  const Core = globalThis.FurnitureJsonCore;
  if (!Core) {
    throw new Error("FurnitureJsonCore 未加载。");
  }

  const STORAGE_KEY = "pico.furniture-json-portal.v1";
  const ROOM_LABELS = {
    living_room: "客厅",
    dining_room: "餐厅",
    bedroom: "卧室",
    study: "书房",
    office: "办公室",
    entryway: "玄关",
    kitchen: "厨房",
    bathroom: "浴室",
    balcony: "阳台",
    commercial: "商业空间",
    other: "其他",
  };
  const COLOR_ROLE_LABELS = {
    primary: "主色",
    secondary: "辅色",
    accent: "点缀色",
  };
  const MATERIAL_LABELS = {
    natural_wood: "天然木材",
    engineered_wood: "人造板材",
    fabric: "织物",
    leather: "皮革",
    metal: "金属",
    stone: "石材",
    concrete: "混凝土",
    glass: "玻璃",
    ceramic: "陶瓷",
    plastic: "塑料",
    rattan: "藤编",
    mixed: "混合材质",
    other: "其他",
  };
  const ERROR_PATH_TO_BINDING = {
    "identity.id": "identity.id",
    "identity.name": "identity.name",
    "identity.description": "identity.description",
    "identity.status": "identity.status",
    "classification.category": "classification.category",
    "asset.glb_url": "asset.glbUrl",
    "asset.thumbnail_url": "asset.thumbnailUrl",
    "asset.file_size_bytes": "asset.fileSizeBytes",
    "asset.sha256": "asset.sha256",
    "source_and_license.provider": "source.provider",
    "source_and_license.asset_id": "source.assetId",
    "source_and_license.page_url": "source.pageUrl",
    "source_and_license.author": "source.author",
    "source_and_license.license_spdx": "source.licenseSpdx",
    "source_and_license.license_url": "source.licenseUrl",
    "source_and_license.attribution_required":
      "source.attributionRequired",
    "source_and_license.hosting_policy": "source.hostingPolicy",
    "geometry.width_m": "geometry.widthM",
    "geometry.depth_m": "geometry.depthM",
    "geometry.height_m": "geometry.heightM",
    "geometry.vertex_count": "geometry.vertexCount",
    "geometry.triangle_count": "geometry.triangleCount",
    "geometry.material_count": "geometry.materialCount",
    "geometry.texture_count": "geometry.textureCount",
    "coordinate_system.up_axis": "coordinates.upAxis",
    "coordinate_system.forward_axis": "coordinates.forwardAxis",
    "coordinate_system.origin_rule": "coordinates.originRule",
    "construction.assembly_type": "construction.assemblyType",
    "construction.detachable": "construction.detachable",
    "construction.support_type": "construction.supportType",
    "placement.support_surface": "placement.supportSurface",
    "placement.against_wall": "placement.againstWall",
    "placement.front_clearance_m": "placement.frontClearanceM",
    "placement.side_clearance_m": "placement.sideClearanceM",
    "style_assessment.assessment_notes": "assessmentNotes",
    notes: "notes",
  };

  const form = document.querySelector("#model-form");
  const preview = document.querySelector("#json-preview");
  const issueSummary = document.querySelector("#issue-summary");
  const issueList = document.querySelector("#issue-list");
  const validityBadge = document.querySelector("#validity-badge");
  const copyButton = document.querySelector("#copy-json");
  const downloadButton = document.querySelector("#download-json");
  const completionText = document.querySelector("#completion-text");
  const progressTrack = document.querySelector(".progress-track");
  const progressFill = document.querySelector("#progress-fill");
  const saveState = document.querySelector("#save-state");
  const toast = document.querySelector("#toast");

  let state = restoreDraft();
  let latestRecord = null;
  let latestValidation = null;
  let saveTimer = null;
  let toastTimer = null;

  renderRooms();
  bindStaticFields();
  bindActions();
  refreshView();

  function bindActions() {
    document.querySelector("#add-color").addEventListener("click", () => {
      if (state.colors.length >= 12) {
        showToast("最多添加 12 种颜色。");
        return;
      }
      state.colors.push({
        hex: "#D8C6A3",
        role: state.colors.length === 0 ? "primary" : "secondary",
        coveragePercent: "",
      });
      refreshView();
    });

    document.querySelector("#add-material").addEventListener("click", () => {
      if (state.materials.length >= 12) {
        showToast("最多添加 12 种材质。");
        return;
      }
      state.materials.push({
        materialId: "natural_wood",
        coveragePercent: "",
      });
      refreshView();
    });

    document.querySelector("#load-example").addEventListener("click", () => {
      if (!window.confirm("载入示例会覆盖当前表单，是否继续？")) return;
      state = Core.exampleState();
      refreshView();
      showToast("已载入完整示例，可直接修改。");
    });

    document.querySelector("#reset-form").addEventListener("click", () => {
      if (!window.confirm("确定清空当前表单和本地草稿吗？")) return;
      state = Core.defaultState();
      try {
        localStorage.removeItem(STORAGE_KEY);
      } catch {
        // Storage is optional. The form still works without it.
      }
      refreshView();
      showToast("表单已重置。");
    });

    copyButton.addEventListener("click", copyJson);
    downloadButton.addEventListener("click", downloadJson);
  }

  function bindStaticFields() {
    for (const input of form.querySelectorAll("[data-bind]")) {
      const eventName =
        input.tagName === "SELECT" || input.type === "checkbox"
          ? "change"
          : "input";
      input.addEventListener(eventName, () => {
        setPath(state, input.dataset.bind, input.value);
        updateOutput();
      });
    }
  }

  function renderRooms() {
    const container = document.querySelector("#room-options");
    container.replaceChildren();
    for (const room of Core.ENUMS.roomTypes) {
      const label = element("label", "chip");
      const input = document.createElement("input");
      input.type = "checkbox";
      input.value = room;
      input.checked = state.classification.roomTypes.includes(room);
      input.addEventListener("change", () => {
        const selected = new Set(state.classification.roomTypes);
        if (input.checked) selected.add(room);
        else selected.delete(room);
        state.classification.roomTypes = [...selected];
        updateOutput();
      });
      const text = element("span");
      text.textContent = ROOM_LABELS[room] ?? room;
      label.append(input, text);
      container.append(label);
    }
  }

  function renderColors() {
    const container = document.querySelector("#color-list");
    container.replaceChildren();
    if (state.colors.length === 0) {
      const empty = element("p", "empty-row");
      empty.textContent = "尚未添加颜色。建议至少记录一个主色。";
      container.append(empty);
      return;
    }
    state.colors.forEach((row, index) => {
      const wrapper = element("div", "array-row");

      const colorField = fieldShell("颜色");
      const control = element("div", "color-control");
      const input = document.createElement("input");
      input.type = "color";
      input.value = validColor(row.hex) ? row.hex : "#D8C6A3";
      input.setAttribute("aria-label", `第 ${index + 1} 个颜色`);
      const value = element("span", "hex-value");
      value.textContent = input.value.toUpperCase();
      input.addEventListener("input", () => {
        row.hex = input.value.toUpperCase();
        value.textContent = row.hex;
        updateOutput();
      });
      control.append(input, value);
      colorField.append(control);

      const roleField = fieldShell("用途");
      const role = document.createElement("select");
      for (const optionValue of Core.ENUMS.colorRoles) {
        role.append(option(optionValue, COLOR_ROLE_LABELS[optionValue]));
      }
      role.value = row.role;
      role.addEventListener("change", () => {
        row.role = role.value;
        updateOutput();
      });
      roleField.append(role);

      const coverageField = fieldShell("占比 %");
      const coverage = numberInput(row.coveragePercent, 0, 100, 1);
      coverage.placeholder = "可选";
      coverage.addEventListener("input", () => {
        row.coveragePercent = coverage.value;
        updateOutput();
      });
      coverageField.append(coverage);

      const remove = removeButton(`删除第 ${index + 1} 个颜色`, () => {
        state.colors.splice(index, 1);
        refreshView();
      });
      wrapper.append(colorField, roleField, coverageField, remove);
      container.append(wrapper);
    });
  }

  function renderMaterials() {
    const container = document.querySelector("#material-list");
    container.replaceChildren();
    if (state.materials.length === 0) {
      const empty = element("p", "empty-row");
      empty.textContent = "尚未添加材质。";
      container.append(empty);
      return;
    }
    state.materials.forEach((row, index) => {
      const wrapper = element("div", "array-row material-row");
      const materialField = fieldShell("材质");
      const select = document.createElement("select");
      for (const id of Core.ENUMS.materialIds) {
        select.append(option(id, MATERIAL_LABELS[id] ?? id));
      }
      select.value = row.materialId;
      select.addEventListener("change", () => {
        row.materialId = select.value;
        updateOutput();
      });
      materialField.append(select);

      const coverageField = fieldShell("占比 %");
      const coverage = numberInput(row.coveragePercent, 0, 100, 1);
      coverage.placeholder = "可选";
      coverage.addEventListener("input", () => {
        row.coveragePercent = coverage.value;
        updateOutput();
      });
      coverageField.append(coverage);

      const remove = removeButton(`删除第 ${index + 1} 个材质`, () => {
        state.materials.splice(index, 1);
        refreshView();
      });
      wrapper.append(materialField, coverageField, remove);
      container.append(wrapper);
    });
  }

  function renderFeatures() {
    const container = document.querySelector("#feature-list");
    container.replaceChildren();
    for (const definition of Core.FEATURES) {
      const entry = state.features[definition.key];
      container.append(
        sliderRow({
          definition,
          checked: entry.observed,
          percent: entry.percent,
          switchLabel: "已观察",
          onToggle(checked) {
            entry.observed = checked;
            refreshView();
          },
          onValue(percent) {
            entry.percent = percent;
            updateOutput();
          },
        }),
      );
    }
  }

  function renderStyles() {
    const container = document.querySelector("#style-list");
    container.replaceChildren();
    for (const definition of Core.STYLES) {
      const entry = state.styles[definition.key];
      container.append(
        sliderRow({
          definition,
          checked: entry.assessed,
          percent: entry.percent,
          switchLabel: "已评估",
          onToggle(checked) {
            entry.assessed = checked;
            refreshView();
          },
          onValue(percent) {
            entry.percent = percent;
            updateOutput();
          },
        }),
      );
      if (definition.key === "style.chinese") {
        const variants = element("div", "variant-group");
        variants.setAttribute("aria-label", "中式细分");
        for (const variant of Core.CHINESE_VARIANTS) {
          const variantEntry = entry.variants[variant.key];
          variants.append(
            sliderRow({
              definition: variant,
              checked: variantEntry.assessed,
              percent: variantEntry.percent,
              switchLabel: "已评估",
              onToggle(checked) {
                variantEntry.assessed = checked;
                refreshView();
              },
              onValue(percent) {
                variantEntry.percent = percent;
                updateOutput();
              },
            }),
          );
        }
        container.append(variants);
      }
    }
  }

  function sliderRow({
    definition,
    checked,
    percent,
    switchLabel,
    onToggle,
    onValue,
  }) {
    const wrapper = element("div", `slider-row${checked ? " active" : ""}`);
    const copy = element("div", "slider-copy");
    const title = document.createElement("strong");
    title.textContent = definition.label;
    const help = document.createElement("small");
    help.textContent = definition.help;
    copy.append(title, help);

    const controls = document.createElement("div");
    const switchLine = element("label", "switch-line");
    const toggle = document.createElement("input");
    toggle.type = "checkbox";
    toggle.checked = checked;
    toggle.setAttribute("aria-label", `${definition.label}${switchLabel}`);
    toggle.addEventListener("change", () => onToggle(toggle.checked));
    const toggleText = document.createElement("span");
    toggleText.textContent = switchLabel;
    switchLine.append(toggle, toggleText);

    const rangeWrap = element("div", "range-wrap");
    const range = document.createElement("input");
    range.type = "range";
    range.min = "0";
    range.max = "100";
    range.step = "1";
    range.value = String(percent);
    range.disabled = !checked;
    range.setAttribute("aria-label", `${definition.label}强度`);
    const output = document.createElement("output");
    output.textContent = checked ? `${percent}` : "—";
    range.addEventListener("input", () => {
      const value = Number(range.value);
      output.textContent = String(value);
      onValue(value);
    });
    rangeWrap.append(range, output);
    controls.append(switchLine, rangeWrap);
    wrapper.append(copy, controls);
    return wrapper;
  }

  function refreshView() {
    for (const input of form.querySelectorAll("[data-bind]")) {
      input.value = getPath(state, input.dataset.bind) ?? "";
    }
    renderRooms();
    renderColors();
    renderMaterials();
    renderFeatures();
    renderStyles();
    updateOutput();
  }

  function updateOutput() {
    latestRecord = Core.buildModelRecord(state, {generatedAt: null});
    latestValidation = Core.validateModelRecord(latestRecord);
    preview.textContent = Core.formatJson(latestRecord);
    updateIssues();
    updateCompletion();
    scheduleDraftSave();
  }

  function updateIssues() {
    const {valid, errors, warnings} = latestValidation;
    validityBadge.textContent = valid ? "可下载" : `${errors.length} 个错误`;
    validityBadge.classList.toggle("invalid", !valid);
    copyButton.disabled = !valid;
    downloadButton.disabled = !valid;
    markInvalidFields(errors);

    issueList.replaceChildren();
    const visible = valid ? warnings.slice(0, 4) : errors.slice(0, 5);
    if (visible.length === 0) {
      issueSummary.textContent = "结构有效，没有发现问题。";
      return;
    }
    issueSummary.textContent = valid
      ? `${warnings.length} 条建议，不影响下载：`
      : "请先修正以下内容：";
    for (const item of visible) {
      const row = document.createElement("li");
      row.textContent = `${item.path}：${item.message}`;
      issueList.append(row);
    }
  }

  function markInvalidFields(errors) {
    for (const input of form.querySelectorAll("[data-bind]")) {
      input.removeAttribute("aria-invalid");
      input.removeAttribute("data-error");
      input.removeAttribute("title");
    }
    for (const error of errors) {
      const binding = ERROR_PATH_TO_BINDING[error.path];
      if (!binding) continue;
      const input = form.querySelector(`[data-bind="${binding}"]`);
      if (!input) continue;
      input.setAttribute("aria-invalid", "true");
      input.dataset.error = error.message;
      input.title = error.message;
    }
  }

  function updateCompletion() {
    const checks = [
      Boolean(latestRecord.identity.id),
      Boolean(latestRecord.identity.name),
      Boolean(latestRecord.classification.category),
      latestRecord.classification.room_types.length > 0,
      Boolean(latestRecord.asset.glb_url),
      Boolean(latestRecord.source_and_license.provider),
      Boolean(latestRecord.source_and_license.license_spdx),
      [
        latestRecord.geometry.width_m,
        latestRecord.geometry.depth_m,
        latestRecord.geometry.height_m,
      ].every((value) => typeof value === "number" && value > 0),
      latestRecord.appearance.colors.length > 0,
      latestRecord.appearance.materials.length > 0,
      Object.values(latestRecord.feature_scores).some(
        (value) => value !== null,
      ),
      Core.STYLES.some(
        ({key}) =>
          latestRecord.style_assessment.scores[key].score !== null,
      ),
    ];
    const percent = Math.round(
      (checks.filter(Boolean).length / checks.length) * 100,
    );
    progressFill.style.width = `${percent}%`;
    progressTrack.setAttribute("aria-valuenow", String(percent));
    completionText.textContent = latestValidation.valid
      ? `${percent}% · 已满足下载条件`
      : `${percent}% · 先填写必填项`;
  }

  function scheduleDraftSave() {
    saveState.textContent = "正在保存本地草稿…";
    window.clearTimeout(saveTimer);
    saveTimer = window.setTimeout(() => {
      try {
        localStorage.setItem(STORAGE_KEY, Core.serializeDraft(state));
        saveState.textContent = "草稿已保存在这个浏览器";
      } catch {
        saveState.textContent = "浏览器禁止保存草稿；下载功能仍可用";
      }
    }, 250);
  }

  function restoreDraft() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return Core.defaultState();
      const restored = Core.sanitizeDraft(raw);
      if (restored.error) {
        localStorage.removeItem(STORAGE_KEY);
        window.setTimeout(() => showToast(restored.error), 0);
      }
      return restored.state;
    } catch {
      return Core.defaultState();
    }
  }

  function recordForExport() {
    const record = Core.buildModelRecord(state, {
      generatedAt: new Date().toISOString(),
    });
    const validation = Core.validateModelRecord(record);
    return validation.valid ? record : null;
  }

  async function copyJson() {
    const record = recordForExport();
    if (!record) return;
    const text = Core.formatJson(record);
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text);
      } else {
        fallbackCopy(text);
      }
      showToast("JSON 已复制。");
    } catch {
      try {
        fallbackCopy(text);
        showToast("JSON 已复制。");
      } catch {
        showToast("复制失败，请使用下载按钮。");
      }
    }
  }

  function fallbackCopy(text) {
    const textarea = document.createElement("textarea");
    textarea.value = text;
    textarea.setAttribute("readonly", "");
    textarea.style.position = "fixed";
    textarea.style.opacity = "0";
    document.body.append(textarea);
    textarea.select();
    const copied = document.execCommand("copy");
    textarea.remove();
    if (!copied) throw new Error("copy failed");
  }

  function downloadJson() {
    const record = recordForExport();
    if (!record) return;
    const blob = new Blob([Core.formatJson(record)], {
      type: "application/json;charset=utf-8",
    });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = Core.downloadFilename(record);
    document.body.append(anchor);
    anchor.click();
    anchor.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 0);
    showToast(`已下载 ${Core.downloadFilename(record)}`);
  }

  function showToast(message) {
    window.clearTimeout(toastTimer);
    toast.textContent = message;
    toast.classList.add("visible");
    toastTimer = window.setTimeout(() => {
      toast.classList.remove("visible");
    }, 2600);
  }

  function getPath(object, path) {
    return path.split(".").reduce((value, key) => value?.[key], object);
  }

  function setPath(object, path, value) {
    const keys = path.split(".");
    const final = keys.pop();
    const parent = keys.reduce((target, key) => target[key], object);
    parent[final] = value;
  }

  function fieldShell(labelText) {
    const label = element("label", "field");
    const title = document.createElement("span");
    title.textContent = labelText;
    label.append(title);
    return label;
  }

  function numberInput(value, min, max, step) {
    const input = document.createElement("input");
    input.type = "number";
    input.min = String(min);
    input.max = String(max);
    input.step = String(step);
    input.inputMode = "numeric";
    input.value = value;
    return input;
  }

  function option(value, label) {
    const item = document.createElement("option");
    item.value = value;
    item.textContent = label;
    return item;
  }

  function removeButton(label, handler) {
    const button = element("button", "remove-button");
    button.type = "button";
    button.setAttribute("aria-label", label);
    button.title = label;
    button.textContent = "×";
    button.addEventListener("click", handler);
    return button;
  }

  function element(tag, className = "") {
    const node = document.createElement(tag);
    if (className) node.className = className;
    return node;
  }

  function validColor(value) {
    return /^#[0-9A-Fa-f]{6}$/.test(value);
  }
})();
