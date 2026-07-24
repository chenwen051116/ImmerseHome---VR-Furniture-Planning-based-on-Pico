(function attachFurnitureJsonCore(root) {
  "use strict";

  const SCHEMA_VERSION = 1;
  const DRAFT_SCHEMA_VERSION = 1;
  const ID_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
  const SHA256_PATTERN = /^[a-f0-9]{64}$/;
  const HEX_PATTERN = /^#[A-Fa-f0-9]{6}$/;

  const ENUMS = Object.freeze({
    statuses: ["draft", "reviewed", "approved"],
    categories: [
      "sofa",
      "sofa_bed",
      "lounge_chair",
      "dining_chair",
      "office_chair",
      "stool",
      "bench",
      "coffee_table",
      "dining_table",
      "desk",
      "bed",
      "nightstand",
      "cabinet",
      "wardrobe",
      "shelf",
      "lighting",
      "decor",
      "other",
    ],
    roomTypes: [
      "living_room",
      "dining_room",
      "bedroom",
      "study",
      "office",
      "entryway",
      "kitchen",
      "bathroom",
      "balcony",
      "commercial",
      "other",
    ],
    hostingPolicies: ["self_hosted", "external_only", "blocked"],
    upAxes: ["+Y", "+Z"],
    forwardAxes: ["+Z", "-Z", "+X", "-X", "+Y", "-Y"],
    originRules: ["bottom_center", "center", "custom", "unknown"],
    colorRoles: ["primary", "secondary", "accent"],
    materialIds: [
      "natural_wood",
      "engineered_wood",
      "fabric",
      "leather",
      "metal",
      "stone",
      "concrete",
      "glass",
      "ceramic",
      "plastic",
      "rattan",
      "mixed",
      "other",
    ],
    assemblyTypes: ["fixed", "knock_down", "modular", "unknown"],
    supportTypes: [
      "floor_base",
      "legs",
      "pedestal",
      "wall_mounted",
      "suspended",
      "mixed",
      "unknown",
    ],
    supportSurfaces: ["floor", "wall", "ceiling", "tabletop", "other"],
  });

  const FEATURES = Object.freeze([
    {
      key: "form.rectilinear",
      label: "直线感",
      help: "轮廓和主要结构以直线、直角为主。",
    },
    {
      key: "form.curved",
      label: "曲线感",
      help: "轮廓、扶手或支撑结构具有明显曲线。",
    },
    {
      key: "form.symmetrical",
      label: "对称性",
      help: "正面或整体结构左右对称的程度。",
    },
    {
      key: "form.low_profile",
      label: "低矮感",
      help: "家具视觉重心低、横向延展明显。",
    },
    {
      key: "form.visual_weight",
      label: "视觉厚重",
      help: "体块、支撑和轮廓给人的厚重程度。",
    },
    {
      key: "form.geometric_simplicity",
      label: "几何简洁",
      help: "造型是否由少量清晰几何形组成。",
    },
    {
      key: "surface.ornamentation",
      label: "装饰程度",
      help: "雕花、纹样、镶嵌和非功能装饰的丰富程度。",
    },
    {
      key: "surface.roughness",
      label: "表面粗糙",
      help: "表面从光滑到粗粝、哑光的程度。",
    },
    {
      key: "surface.handcrafted",
      label: "手工感",
      help: "不完全一致、手工连接或手工处理的可见程度。",
    },
    {
      key: "surface.aged",
      label: "做旧感",
      help: "磨损、氧化、褪色或时间痕迹的程度。",
    },
    {
      key: "surface.natural_imperfection",
      label: "天然缺陷",
      help: "木结、石纹、裂纹等天然不完美的可见程度。",
    },
    {
      key: "construction.modular",
      label: "模块化",
      help: "可组合、扩展或替换模块的可能性。",
    },
    {
      key: "construction.flat_pack_likelihood",
      label: "平板包装倾向",
      help: "结构是否像可拆装、平板运输的标准化产品。",
    },
    {
      key: "construction.exposed_metal",
      label: "金属外露",
      help: "钢架、螺栓、铆钉或金属连接件外露程度。",
    },
  ]);

  const STYLES = Object.freeze([
    {
      key: "style.nordic",
      label: "北欧风",
      help: "浅木、低饱和、轻盈、自然材质与功能主义。",
    },
    {
      key: "style.wabi_sabi",
      label: "侘寂风",
      help: "自然、不完美、哑光、粗粝、安静和时间感。",
    },
    {
      key: "style.ikea_functional",
      label: "宜家风",
      help: "北欧大众功能主义、模块化、标准化和易组合。",
    },
    {
      key: "style.chinese",
      label: "中式",
      help: "中国传统结构、比例、木作、纹样或再设计线索。",
    },
    {
      key: "style.industrial",
      label: "工业风",
      help: "裸露金属、旧木、混凝土、结构外显和工厂语义。",
    },
  ]);

  const CHINESE_VARIANTS = Object.freeze([
    {
      key: "variant.traditional_chinese",
      label: "传统中式",
      help: "传统造型、深色实木、榫卯、庄重对称和文化纹样。",
    },
    {
      key: "variant.neo_chinese",
      label: "新中式",
      help: "保留中式线索，同时简化结构并结合当代材质与尺度。",
    },
  ]);

  function featureDefaults() {
    return Object.fromEntries(
      FEATURES.map(({key}) => [key, {observed: false, percent: 50}]),
    );
  }

  function styleDefaults() {
    const scores = Object.fromEntries(
      STYLES.map(({key}) => [key, {assessed: false, percent: 50}]),
    );
    scores["style.chinese"].variants = Object.fromEntries(
      CHINESE_VARIANTS.map(({key}) => [
        key,
        {assessed: false, percent: 50},
      ]),
    );
    return scores;
  }

  function defaultState() {
    return {
      identity: {
        id: "",
        name: "",
        description: "",
        status: "draft",
      },
      classification: {
        category: "",
        roomTypes: [],
      },
      asset: {
        glbUrl: "",
        thumbnailUrl: "",
        fileSizeBytes: "",
        sha256: "",
      },
      source: {
        provider: "",
        assetId: "",
        pageUrl: "",
        author: "",
        licenseSpdx: "",
        licenseUrl: "",
        attributionRequired: "",
        hostingPolicy: "",
      },
      geometry: {
        widthM: "",
        depthM: "",
        heightM: "",
        vertexCount: "",
        triangleCount: "",
        materialCount: "",
        textureCount: "",
      },
      coordinates: {
        upAxis: "+Y",
        forwardAxis: "+Z",
        originRule: "bottom_center",
      },
      colors: [],
      materials: [],
      features: featureDefaults(),
      construction: {
        assemblyType: "unknown",
        detachable: "",
        supportType: "unknown",
      },
      placement: {
        supportSurface: "floor",
        againstWall: "",
        frontClearanceM: "",
        sideClearanceM: "",
      },
      styles: styleDefaults(),
      assessmentNotes: "",
      notes: "",
    };
  }

  function exampleState() {
    const state = defaultState();
    state.identity = {
      id: "chair-oak-001",
      name: "浅木模块化餐椅",
      description: "浅色木框架与米色软包餐椅，结构简洁。",
      status: "reviewed",
    };
    state.classification = {
      category: "dining_chair",
      roomTypes: ["dining_room", "living_room"],
    };
    state.asset.glbUrl =
      "https://example.com/models/chair-oak-001.glb";
    state.asset.thumbnailUrl =
      "https://example.com/thumbs/chair-oak-001.jpg";
    state.asset.fileSizeBytes = "32036";
    state.source = {
      provider: "example_library",
      assetId: "chair-oak-001",
      pageUrl: "https://example.com/assets/chair-oak-001",
      author: "Example Designer",
      licenseSpdx: "CC0-1.0",
      licenseUrl:
        "https://creativecommons.org/publicdomain/zero/1.0/",
      attributionRequired: "false",
      hostingPolicy: "self_hosted",
    };
    state.geometry = {
      widthM: "0.46",
      depthM: "0.52",
      heightM: "0.82",
      vertexCount: "905",
      triangleCount: "1380",
      materialCount: "2",
      textureCount: "0",
    };
    state.colors = [
      {hex: "#D8C6A3", role: "primary", coveragePercent: "80"},
      {hex: "#F2EEE6", role: "secondary", coveragePercent: "20"},
    ];
    state.materials = [
      {materialId: "natural_wood", coveragePercent: "70"},
      {materialId: "fabric", coveragePercent: "30"},
    ];
    setObserved(state, "form.rectilinear", 70);
    setObserved(state, "form.curved", 25);
    setObserved(state, "form.symmetrical", 85);
    setObserved(state, "form.visual_weight", 35);
    setObserved(state, "form.geometric_simplicity", 90);
    setObserved(state, "surface.ornamentation", 10);
    setObserved(state, "surface.roughness", 25);
    setObserved(state, "surface.aged", 0);
    setObserved(state, "construction.modular", 45);
    setObserved(state, "construction.flat_pack_likelihood", 70);
    setObserved(state, "construction.exposed_metal", 0);
    state.construction = {
      assemblyType: "knock_down",
      detachable: "true",
      supportType: "legs",
    };
    state.placement = {
      supportSurface: "floor",
      againstWall: "false",
      frontClearanceM: "0.6",
      sideClearanceM: "0.35",
    };
    setAssessed(state, "style.nordic", 86);
    setAssessed(state, "style.wabi_sabi", 30);
    setAssessed(state, "style.ikea_functional", 82);
    setAssessed(state, "style.chinese", 8);
    setAssessed(state, "style.industrial", 12);
    setVariantAssessed(
      state,
      "variant.traditional_chinese",
      3,
    );
    setVariantAssessed(state, "variant.neo_chinese", 12);
    state.assessmentNotes =
      "分数由录入人员根据造型、结构和材料直接填写。";
    state.notes = "示例数据仅用于演示表单和 JSON 结构。";
    return state;
  }

  function setObserved(state, key, percent) {
    if (state.features[key]) {
      state.features[key] = {observed: true, percent};
    }
  }

  function setAssessed(state, key, percent) {
    if (state.styles[key]) {
      state.styles[key].assessed = true;
      state.styles[key].percent = percent;
    }
  }

  function setVariantAssessed(state, key, percent) {
    const variant = state.styles["style.chinese"].variants[key];
    if (variant) {
      variant.assessed = true;
      variant.percent = percent;
    }
  }

  function nullableText(value) {
    const text = String(value ?? "").trim();
    return text === "" ? null : text;
  }

  function nullableNumber(value) {
    if (value === "" || value === null || value === undefined) {
      return null;
    }
    const number = Number(value);
    return Number.isFinite(number) ? number : value;
  }

  function nullableInteger(value) {
    return nullableNumber(value);
  }

  function nullableBoolean(value) {
    if (value === true || value === "true") {
      return true;
    }
    if (value === false || value === "false") {
      return false;
    }
    return null;
  }

  function ratioFromPercent(value) {
    if (value === "" || value === null || value === undefined) {
      return null;
    }
    const number = Number(value);
    if (!Number.isFinite(number)) {
      return value;
    }
    return Math.round((number / 100) * 100) / 100;
  }

  function scoreEntry(input) {
    if (!input?.assessed) {
      return {score: null, score_origin: null};
    }
    return {
      score: ratioFromPercent(input.percent),
      score_origin: "human_input",
    };
  }

  function buildModelRecord(state, {generatedAt = null} = {}) {
    const source = state ?? defaultState();
    const featureScores = Object.fromEntries(
      FEATURES.map(({key}) => {
        const input = source.features?.[key];
        return [
          key,
          input?.observed ? ratioFromPercent(input.percent) : null,
        ];
      }),
    );
    const styleScores = Object.fromEntries(
      STYLES.map(({key}) => [key, scoreEntry(source.styles?.[key])]),
    );
    styleScores["style.chinese"].variants = Object.fromEntries(
      CHINESE_VARIANTS.map(({key}) => [
        key,
        scoreEntry(source.styles?.["style.chinese"]?.variants?.[key]),
      ]),
    );

    return {
      schema_version: SCHEMA_VERSION,
      generated_at: generatedAt,
      identity: {
        id: String(source.identity?.id ?? "").trim(),
        name: String(source.identity?.name ?? "").trim(),
        description: nullableText(source.identity?.description),
        status: source.identity?.status ?? "draft",
      },
      classification: {
        category: String(source.classification?.category ?? ""),
        room_types: Array.isArray(source.classification?.roomTypes)
          ? [...source.classification.roomTypes]
          : [],
      },
      asset: {
        format: "glb",
        glb_url: nullableText(source.asset?.glbUrl),
        thumbnail_url: nullableText(source.asset?.thumbnailUrl),
        file_size_bytes: nullableInteger(source.asset?.fileSizeBytes),
        sha256: nullableText(source.asset?.sha256)?.toLowerCase() ?? null,
      },
      source_and_license: {
        provider: nullableText(source.source?.provider),
        asset_id: nullableText(source.source?.assetId),
        page_url: nullableText(source.source?.pageUrl),
        author: nullableText(source.source?.author),
        license_spdx: nullableText(source.source?.licenseSpdx),
        license_url: nullableText(source.source?.licenseUrl),
        attribution_required: nullableBoolean(
          source.source?.attributionRequired,
        ),
        hosting_policy: nullableText(source.source?.hostingPolicy),
      },
      geometry: {
        width_m: nullableNumber(source.geometry?.widthM),
        depth_m: nullableNumber(source.geometry?.depthM),
        height_m: nullableNumber(source.geometry?.heightM),
        vertex_count: nullableInteger(source.geometry?.vertexCount),
        triangle_count: nullableInteger(source.geometry?.triangleCount),
        material_count: nullableInteger(source.geometry?.materialCount),
        texture_count: nullableInteger(source.geometry?.textureCount),
      },
      coordinate_system: {
        unit: "m",
        up_axis: source.coordinates?.upAxis ?? "+Y",
        forward_axis: source.coordinates?.forwardAxis ?? "+Z",
        origin_rule: source.coordinates?.originRule ?? "bottom_center",
      },
      appearance: {
        colors: (source.colors ?? []).map((color) => ({
          hex: String(color.hex ?? "").toUpperCase(),
          role: color.role ?? "primary",
          coverage_ratio: ratioFromPercent(color.coveragePercent),
        })),
        materials: (source.materials ?? []).map((material) => ({
          material_id: material.materialId ?? "other",
          coverage_ratio: ratioFromPercent(material.coveragePercent),
        })),
      },
      feature_scores: featureScores,
      construction: {
        assembly_type: source.construction?.assemblyType ?? "unknown",
        detachable: nullableBoolean(source.construction?.detachable),
        support_type: source.construction?.supportType ?? "unknown",
      },
      placement: {
        support_surface: source.placement?.supportSurface ?? "floor",
        against_wall: nullableBoolean(source.placement?.againstWall),
        front_clearance_m: nullableNumber(
          source.placement?.frontClearanceM,
        ),
        side_clearance_m: nullableNumber(source.placement?.sideClearanceM),
      },
      style_assessment: {
        score_scale: "0_to_1",
        scores: styleScores,
        assessment_notes: nullableText(source.assessmentNotes),
      },
      notes: nullableText(source.notes),
    };
  }

  function issue(path, message, code) {
    return {path, message, code};
  }

  function validateModelRecord(record) {
    const errors = [];
    const warnings = [];

    if (!record || typeof record !== "object") {
      return {
        valid: false,
        errors: [issue("$", "JSON 根对象无效。", "INVALID_ROOT")],
        warnings,
      };
    }

    if (record.schema_version !== SCHEMA_VERSION) {
      errors.push(
        issue(
          "schema_version",
          `schema_version 必须为 ${SCHEMA_VERSION}。`,
          "SCHEMA_VERSION",
        ),
      );
    }
    if (
      record.generated_at !== null &&
      !isIsoTimestamp(record.generated_at)
    ) {
      errors.push(
        issue(
          "generated_at",
          "生成时间必须是 ISO 8601 UTC 字符串或 null。",
          "GENERATED_AT",
        ),
      );
    }
    if (record.asset?.format !== "glb") {
      errors.push(
        issue("asset.format", "资产格式必须固定为 glb。", "ASSET_FORMAT"),
      );
    }
    if (record.coordinate_system?.unit !== "m") {
      errors.push(
        issue(
          "coordinate_system.unit",
          "尺寸单位必须固定为 m。",
          "COORDINATE_UNIT",
        ),
      );
    }
    if (record.style_assessment?.score_scale !== "0_to_1") {
      errors.push(
        issue(
          "style_assessment.score_scale",
          "评分范围必须固定为 0_to_1。",
          "SCORE_SCALE",
        ),
      );
    }

    const id = record.identity?.id ?? "";
    if (!id) {
      errors.push(issue("identity.id", "模型 ID 不能为空。", "ID_REQUIRED"));
    } else if (!ID_PATTERN.test(id) || id.length > 80) {
      errors.push(
        issue(
          "identity.id",
          "模型 ID 只能使用小写字母、数字和单个连字符，最多 80 字符。",
          "ID_FORMAT",
        ),
      );
    }
    validateRequiredText(
      record.identity?.name,
      "identity.name",
      120,
      "名称",
      errors,
    );
    validateOptionalText(
      record.identity?.description,
      "identity.description",
      2000,
      errors,
    );
    validateEnum(
      record.identity?.status,
      ENUMS.statuses,
      "identity.status",
      errors,
    );
    validateEnum(
      record.classification?.category,
      ENUMS.categories,
      "classification.category",
      errors,
      true,
    );
    validateEnumArray(
      record.classification?.room_types,
      ENUMS.roomTypes,
      "classification.room_types",
      errors,
    );

    validateUrl(record.asset?.glb_url, "asset.glb_url", errors);
    validateUrl(
      record.asset?.thumbnail_url,
      "asset.thumbnail_url",
      errors,
    );
    validateNonNegativeInteger(
      record.asset?.file_size_bytes,
      "asset.file_size_bytes",
      errors,
    );
    if (
      record.asset?.sha256 !== null &&
      !SHA256_PATTERN.test(record.asset?.sha256 ?? "")
    ) {
      errors.push(
        issue(
          "asset.sha256",
          "SHA-256 必须是 64 位小写十六进制。",
          "SHA256_FORMAT",
        ),
      );
    }

    for (const [key, value] of Object.entries(
      record.source_and_license ?? {},
    )) {
      if (key.endsWith("_url")) {
        validateUrl(value, `source_and_license.${key}`, errors);
      } else if (
        !["attribution_required", "hosting_policy"].includes(key)
      ) {
        validateOptionalText(
          value,
          `source_and_license.${key}`,
          240,
          errors,
        );
      }
    }
    if (record.source_and_license?.hosting_policy !== null) {
      validateEnum(
        record.source_and_license?.hosting_policy,
        ENUMS.hostingPolicies,
        "source_and_license.hosting_policy",
        errors,
      );
    }
    validateNullableBoolean(
      record.source_and_license?.attribution_required,
      "source_and_license.attribution_required",
      errors,
    );

    for (const key of ["width_m", "depth_m", "height_m"]) {
      validatePositiveNumber(
        record.geometry?.[key],
        `geometry.${key}`,
        errors,
      );
    }
    for (const key of [
      "vertex_count",
      "triangle_count",
      "material_count",
      "texture_count",
    ]) {
      validateNonNegativeInteger(
        record.geometry?.[key],
        `geometry.${key}`,
        errors,
      );
    }

    validateEnum(
      record.coordinate_system?.up_axis,
      ENUMS.upAxes,
      "coordinate_system.up_axis",
      errors,
    );
    validateEnum(
      record.coordinate_system?.forward_axis,
      ENUMS.forwardAxes,
      "coordinate_system.forward_axis",
      errors,
    );
    validateEnum(
      record.coordinate_system?.origin_rule,
      ENUMS.originRules,
      "coordinate_system.origin_rule",
      errors,
    );
    if (
      axisLetter(record.coordinate_system?.up_axis) ===
      axisLetter(record.coordinate_system?.forward_axis)
    ) {
      errors.push(
        issue(
          "coordinate_system.forward_axis",
          "正面轴不能与向上轴共线。",
          "AXES_COLLINEAR",
        ),
      );
    }

    validateColors(record.appearance?.colors, errors, warnings);
    validateMaterials(record.appearance?.materials, errors, warnings);

    for (const {key} of FEATURES) {
      validateNullableScore(
        record.feature_scores?.[key],
        `feature_scores.${key}`,
        errors,
      );
    }
    validateEnum(
      record.construction?.assembly_type,
      ENUMS.assemblyTypes,
      "construction.assembly_type",
      errors,
    );
    validateNullableBoolean(
      record.construction?.detachable,
      "construction.detachable",
      errors,
    );
    validateEnum(
      record.construction?.support_type,
      ENUMS.supportTypes,
      "construction.support_type",
      errors,
    );
    validateEnum(
      record.placement?.support_surface,
      ENUMS.supportSurfaces,
      "placement.support_surface",
      errors,
    );
    validateNullableBoolean(
      record.placement?.against_wall,
      "placement.against_wall",
      errors,
    );
    validatePositiveNumber(
      record.placement?.front_clearance_m,
      "placement.front_clearance_m",
      errors,
    );
    validatePositiveNumber(
      record.placement?.side_clearance_m,
      "placement.side_clearance_m",
      errors,
    );

    for (const {key} of STYLES) {
      validateStyleEntry(
        record.style_assessment?.scores?.[key],
        `style_assessment.scores.${key}`,
        errors,
      );
    }
    for (const {key} of CHINESE_VARIANTS) {
      validateStyleEntry(
        record.style_assessment?.scores?.["style.chinese"]?.variants?.[
          key
        ],
        `style_assessment.scores.style.chinese.variants.${key}`,
        errors,
      );
    }
    validateOptionalText(
      record.style_assessment?.assessment_notes,
      "style_assessment.assessment_notes",
      2000,
      errors,
    );
    validateOptionalText(record.notes, "notes", 2000, errors);

    const dimensions = [
      record.geometry?.width_m,
      record.geometry?.depth_m,
      record.geometry?.height_m,
    ];
    if (dimensions.some((value) => value === null)) {
      warnings.push(
        issue(
          "geometry",
          "建议填写完整的宽、深、高，方便后续筛选和摆放。",
          "DIMENSIONS_INCOMPLETE",
        ),
      );
    }
    if (
      !record.source_and_license?.provider ||
      !record.source_and_license?.license_spdx
    ) {
      warnings.push(
        issue(
          "source_and_license",
          "建议补充来源平台和许可证。",
          "SOURCE_LICENSE_INCOMPLETE",
        ),
      );
    }
    const assessedStyles = STYLES.filter(
      ({key}) =>
        record.style_assessment?.scores?.[key]?.score !== null,
    );
    if (assessedStyles.length === 0) {
      warnings.push(
        issue(
          "style_assessment",
          "尚未评估任何主风格。",
          "STYLE_SCORES_EMPTY",
        ),
      );
    }
    const chinese = record.style_assessment?.scores?.["style.chinese"];
    if (
      chinese?.score === null &&
      CHINESE_VARIANTS.every(
        ({key}) => chinese?.variants?.[key]?.score === null,
      )
    ) {
      warnings.push(
        issue(
          "style_assessment.scores.style.chinese",
          "中式总分和两个细分均未评估。",
          "CHINESE_STYLE_EMPTY",
        ),
      );
    }

    return {valid: errors.length === 0, errors, warnings};
  }

  function validateRequiredText(value, path, max, label, errors) {
    if (typeof value !== "string" || value.trim() === "") {
      errors.push(issue(path, `${label}不能为空。`, "TEXT_REQUIRED"));
    } else if (value.length > max) {
      errors.push(
        issue(path, `${label}不能超过 ${max} 字符。`, "TEXT_TOO_LONG"),
      );
    }
  }

  function validateOptionalText(value, path, max, errors) {
    if (value === null) return;
    if (typeof value !== "string") {
      errors.push(issue(path, "必须是字符串或 null。", "TEXT_TYPE"));
    } else if (value.length > max) {
      errors.push(
        issue(path, `不能超过 ${max} 字符。`, "TEXT_TOO_LONG"),
      );
    }
  }

  function validateUrl(value, path, errors) {
    if (value === null) return;
    if (typeof value !== "string" || value.length > 2048) {
      errors.push(
        issue(path, "URL 必须是最多 2048 字符的字符串。", "URL_TYPE"),
      );
      return;
    }
    try {
      const parsed = new URL(value);
      if (parsed.protocol !== "https:") {
        throw new Error("protocol");
      }
    } catch {
      errors.push(
        issue(path, "URL 必须以 https:// 开头并且格式有效。", "URL_FORMAT"),
      );
    }
  }

  function validateEnum(value, allowed, path, errors, required = false) {
    if (!allowed.includes(value)) {
      errors.push(
        issue(
          path,
          required && !value
            ? "该字段不能为空。"
            : `值必须是：${allowed.join(", ")}。`,
          "ENUM_VALUE",
        ),
      );
    }
  }

  function validateEnumArray(values, allowed, path, errors) {
    if (!Array.isArray(values)) {
      errors.push(issue(path, "必须是数组。", "ARRAY_TYPE"));
      return;
    }
    for (const value of values) {
      if (!allowed.includes(value)) {
        errors.push(
          issue(path, `包含不支持的值：${value}。`, "ENUM_VALUE"),
        );
      }
    }
  }

  function validatePositiveNumber(value, path, errors) {
    if (value === null) return;
    if (typeof value !== "number" || !Number.isFinite(value) || value <= 0) {
      errors.push(issue(path, "必须是大于 0 的数字或 null。", "POSITIVE_NUMBER"));
    }
  }

  function validateNonNegativeInteger(value, path, errors) {
    if (value === null) return;
    if (!Number.isInteger(value) || value < 0) {
      errors.push(
        issue(path, "必须是非负整数或 null。", "NON_NEGATIVE_INTEGER"),
      );
    }
  }

  function validateNullableBoolean(value, path, errors) {
    if (value !== null && typeof value !== "boolean") {
      errors.push(issue(path, "必须是布尔值或 null。", "BOOLEAN_TYPE"));
    }
  }

  function validateNullableScore(value, path, errors) {
    if (value === null) return;
    if (
      typeof value !== "number" ||
      !Number.isFinite(value) ||
      value < 0 ||
      value > 1
    ) {
      errors.push(issue(path, "分数必须在 0.0–1.0 或为 null。", "SCORE_RANGE"));
    }
  }

  function validateStyleEntry(entry, path, errors) {
    if (!entry || typeof entry !== "object") {
      errors.push(issue(path, "风格评分对象缺失。", "STYLE_ENTRY"));
      return;
    }
    validateNullableScore(entry.score, `${path}.score`, errors);
    if (entry.score === null && entry.score_origin !== null) {
      errors.push(
        issue(
          `${path}.score_origin`,
          "未评估分数的来源必须为 null。",
          "STYLE_ORIGIN",
        ),
      );
    }
    if (entry.score !== null && entry.score_origin !== "human_input") {
      errors.push(
        issue(
          `${path}.score_origin`,
          "人工评分来源必须为 human_input。",
          "STYLE_ORIGIN",
        ),
      );
    }
  }

  function validateColors(colors, errors, warnings) {
    if (!Array.isArray(colors)) {
      errors.push(issue("appearance.colors", "颜色必须是数组。", "ARRAY_TYPE"));
      return;
    }
    let sum = 0;
    let known = 0;
    colors.forEach((color, index) => {
      const path = `appearance.colors.${index}`;
      if (!HEX_PATTERN.test(color?.hex ?? "")) {
        errors.push(issue(`${path}.hex`, "颜色必须是 #RRGGBB。", "HEX_FORMAT"));
      }
      validateEnum(color?.role, ENUMS.colorRoles, `${path}.role`, errors);
      validateCoverage(color?.coverage_ratio, `${path}.coverage_ratio`, errors);
      if (typeof color?.coverage_ratio === "number") {
        sum += color.coverage_ratio;
        known += 1;
      }
    });
    validateCoverageSum(sum, known, "appearance.colors", errors, warnings);
  }

  function validateMaterials(materials, errors, warnings) {
    if (!Array.isArray(materials)) {
      errors.push(
        issue("appearance.materials", "材料必须是数组。", "ARRAY_TYPE"),
      );
      return;
    }
    let sum = 0;
    let known = 0;
    materials.forEach((material, index) => {
      const path = `appearance.materials.${index}`;
      validateEnum(
        material?.material_id,
        ENUMS.materialIds,
        `${path}.material_id`,
        errors,
      );
      validateCoverage(
        material?.coverage_ratio,
        `${path}.coverage_ratio`,
        errors,
      );
      if (typeof material?.coverage_ratio === "number") {
        sum += material.coverage_ratio;
        known += 1;
      }
    });
    validateCoverageSum(sum, known, "appearance.materials", errors, warnings);
  }

  function validateCoverage(value, path, errors) {
    if (value === null) return;
    if (
      typeof value !== "number" ||
      !Number.isFinite(value) ||
      value < 0 ||
      value > 1
    ) {
      errors.push(
        issue(path, "占比必须在 0.0–1.0 或为 null。", "COVERAGE_RANGE"),
      );
    }
  }

  function validateCoverageSum(sum, known, path, errors, warnings) {
    if (sum > 1.000001) {
      errors.push(
        issue(path, "已填写占比合计不能超过 100%。", "COVERAGE_SUM"),
      );
    } else if (known > 0 && sum < 0.999999) {
      warnings.push(
        issue(path, "已填写占比不足 100%，剩余部分视为未知。", "COVERAGE_PARTIAL"),
      );
    }
  }

  function axisLetter(axis) {
    return typeof axis === "string" ? axis.slice(-1) : "";
  }

  function isIsoTimestamp(value) {
    if (typeof value !== "string") return false;
    const parsed = new Date(value);
    return (
      Number.isFinite(parsed.getTime()) &&
      parsed.toISOString() === value
    );
  }

  function formatJson(record) {
    return `${JSON.stringify(record, null, 2)}\n`;
  }

  function downloadFilename(record) {
    return `${record.identity.id}.json`;
  }

  function sanitizeDraft(raw) {
    try {
      const parsed = JSON.parse(raw);
      if (
        parsed?.schema_version !== DRAFT_SCHEMA_VERSION ||
        !parsed.state ||
        typeof parsed.state !== "object" ||
        Array.isArray(parsed.state)
      ) {
        return {state: defaultState(), error: "草稿版本或结构无效。"};
      }
      return {state: normalizeState(parsed.state), error: null};
    } catch {
      return {state: defaultState(), error: "草稿 JSON 无法解析。"};
    }
  }

  function serializeDraft(state) {
    return JSON.stringify({
      schema_version: DRAFT_SCHEMA_VERSION,
      state: normalizeState(state),
    });
  }

  function normalizeState(candidate) {
    const base = defaultState();
    if (!candidate || typeof candidate !== "object") {
      return base;
    }
    copyPrimitiveGroup(base.identity, candidate.identity);
    copyPrimitiveGroup(base.asset, candidate.asset);
    copyPrimitiveGroup(base.source, candidate.source);
    copyPrimitiveGroup(base.geometry, candidate.geometry);
    copyPrimitiveGroup(base.coordinates, candidate.coordinates);
    copyPrimitiveGroup(base.construction, candidate.construction);
    copyPrimitiveGroup(base.placement, candidate.placement);

    if (candidate.classification?.category !== undefined) {
      base.classification.category = String(
        candidate.classification.category,
      ).slice(0, 80);
    }
    if (Array.isArray(candidate.classification?.roomTypes)) {
      base.classification.roomTypes = candidate.classification.roomTypes
        .filter((value) => ENUMS.roomTypes.includes(value))
        .slice(0, ENUMS.roomTypes.length);
    }
    base.colors = normalizeRows(candidate.colors, 12, (row) => ({
      hex: typeof row?.hex === "string" ? row.hex.slice(0, 7) : "#D8C6A3",
      role: ENUMS.colorRoles.includes(row?.role) ? row.role : "primary",
      coveragePercent: primitiveString(row?.coveragePercent, 8),
    }));
    base.materials = normalizeRows(candidate.materials, 12, (row) => ({
      materialId: ENUMS.materialIds.includes(row?.materialId)
        ? row.materialId
        : "other",
      coveragePercent: primitiveString(row?.coveragePercent, 8),
    }));
    for (const {key} of FEATURES) {
      const incoming = candidate.features?.[key];
      base.features[key] = {
        observed: incoming?.observed === true,
        percent: clampPercent(incoming?.percent),
      };
    }
    for (const {key} of STYLES) {
      const incoming = candidate.styles?.[key];
      base.styles[key].assessed = incoming?.assessed === true;
      base.styles[key].percent = clampPercent(incoming?.percent);
    }
    for (const {key} of CHINESE_VARIANTS) {
      const incoming =
        candidate.styles?.["style.chinese"]?.variants?.[key];
      base.styles["style.chinese"].variants[key] = {
        assessed: incoming?.assessed === true,
        percent: clampPercent(incoming?.percent),
      };
    }
    base.assessmentNotes = primitiveString(candidate.assessmentNotes, 2000);
    base.notes = primitiveString(candidate.notes, 2000);
    return base;
  }

  function copyPrimitiveGroup(target, source) {
    if (!source || typeof source !== "object") return;
    for (const key of Object.keys(target)) {
      if (source[key] !== undefined) {
        target[key] = primitiveString(source[key], 2048);
      }
    }
  }

  function primitiveString(value, max) {
    if (typeof value === "boolean") return String(value);
    if (typeof value === "number" && Number.isFinite(value)) {
      return String(value);
    }
    return typeof value === "string" ? value.slice(0, max) : "";
  }

  function normalizeRows(rows, max, mapper) {
    return Array.isArray(rows)
      ? rows.filter((row) => row && typeof row === "object").slice(0, max).map(mapper)
      : [];
  }

  function clampPercent(value) {
    const number = Number(value);
    return Number.isFinite(number)
      ? Math.min(100, Math.max(0, Math.round(number)))
      : 50;
  }

  root.FurnitureJsonCore = Object.freeze({
    SCHEMA_VERSION,
    DRAFT_SCHEMA_VERSION,
    ENUMS,
    FEATURES,
    STYLES,
    CHINESE_VARIANTS,
    defaultState,
    exampleState,
    buildModelRecord,
    validateModelRecord,
    formatJson,
    downloadFilename,
    sanitizeDraft,
    serializeDraft,
    normalizeState,
  });
})(globalThis);
