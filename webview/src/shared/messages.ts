import { z } from "zod/v4";

export const OsTypeSchema = z.enum(["MACOS", "WINDOWS", "LINUX"]);
export type OsType = z.infer<typeof OsTypeSchema>;
export const InstallStatusSchema = z.enum(["project", "global", "update", "missing"]);
export type InstallStatus = z.infer<typeof InstallStatusSchema>;
export const SessionSchema = z.object({ sessionKey: z.string(), title: z.string().nullish().transform(value => value?.trim() || "新对话"), createdAt: z.string().optional(), updatedAt: z.string().optional() }).passthrough();
export type Session = z.infer<typeof SessionSchema>;
export const ChatMessageSchema = z.object({ id: z.string(), role: z.enum(["user", "assistant"]), content: z.string(), runKey: z.string().optional(), createdAt: z.string().optional() });
export type ChatMessage = z.infer<typeof ChatMessageSchema>;
const optionalText = z.string().nullish().transform(value => value ?? undefined);
export const RecommendationSchema = z.object({ skillKey: z.string(), runKey: optionalText, name: optionalText, description: optionalText, version: optionalText, versionId: z.number().int().positive().nullish().transform(value => value ?? undefined), reason: optionalText, detailPath: optionalText, status: InstallStatusSchema.optional().default("missing"), localVersion: optionalText });
const RecommendationsSchema = z.array(z.unknown()).transform(items => items.flatMap(item => {
  const parsed = RecommendationSchema.safeParse(item);
  return parsed.success ? [parsed.data] : [];
}));
export type Recommendation = z.infer<typeof RecommendationSchema>;
export const UpdateScopeSchema = z.enum(["project", "global"]);
export const SkillUpdateSchema = z.object({ skillKey: z.string(), name: z.string().optional(), localVersion: z.string(), latestVersion: z.string(), latestVersionId: z.number().int().positive(), scopes: z.array(UpdateScopeSchema), installing: z.boolean().optional() });
export type SkillUpdate = z.infer<typeof SkillUpdateSchema>;

export const HostMessageSchema = z.discriminatedUnion("type", [
  z.object({ type: z.literal("wiki-metadata"), documentId: z.number().int().positive(), title: z.string() }),
  z.object({ type: z.literal("asdm-metadata"), assetId: z.number().int().positive(), title: z.string() }),
  z.object({ type: z.literal("state"), authenticated: z.boolean(), loginPending: z.boolean().optional(), userCode: z.string().optional(), apiBaseUrl: z.string().optional(), webBaseUrl: z.string().optional(), osType: OsTypeSchema, sessions: z.array(SessionSchema), currentSessionKey: z.string().optional(), currentRunKey: z.string().optional(), messages: z.array(ChatMessageSchema), recommendations: RecommendationsSchema, asdmInstalled: z.boolean().optional(), skillUpdates: z.array(SkillUpdateSchema).default([]), updatesChecking: z.boolean().default(false), updatesInstalling: z.boolean().default(false), updateError: z.string().optional(), running: z.boolean(), connection: z.enum(["idle", "connecting", "reconnecting", "closed"]), error: z.string().optional() }),
  z.object({ type: z.literal("notice"), level: z.enum(["info", "error"]), message: z.string() }),
]);
export type HostMessage = z.infer<typeof HostMessageSchema>;
export const WebviewMessageSchema = z.discriminatedUnion("type", [
  z.object({ type: z.literal("ready") }), z.object({ type: z.literal("login") }), z.object({ type: z.literal("logout") }), z.object({ type: z.literal("new-session") }), z.object({ type: z.literal("refresh-sessions") }),
  z.object({ type: z.literal("select-session"), sessionKey: z.string() }), z.object({ type: z.literal("delete-session"), sessionKey: z.string() }),
  z.object({ type: z.literal("send-message"), content: z.string().trim().min(1).max(10000) }), z.object({ type: z.literal("cancel-run") }),
  z.object({ type: z.literal("install-skill"), runKey: z.string(), skillKey: z.string() }), z.object({ type: z.literal("install-all"), runKey: z.string() }),
  z.object({ type: z.literal("open-detail"), detailPath: z.string() }), z.object({ type: z.literal("install-updates"), skillKeys: z.array(z.string()).min(1) }), z.object({ type: z.literal("dismiss-updates") }), z.object({ type: z.literal("retry-updates") }), z.object({ type: z.literal("refresh") }),
  z.object({ type: z.literal("download-wiki"), documentIds: z.array(z.number().int().positive()).min(1).max(20) }),
  z.object({ type: z.literal("install-asdm-tool"), assetId: z.number().int().positive() }),
  z.object({ type: z.literal("wiki-metadata-request"), documentId: z.number().int().positive() }),
  z.object({ type: z.literal("asdm-metadata-request"), assetId: z.number().int().positive() }),
  z.object({ type: z.literal("open-external-url"), url: z.string().min(1).max(2048) }),
  z.object({ type: z.literal("configure"), apiBaseUrl: z.string().url(), webBaseUrl: z.string().optional() }),
]);
export type WebviewMessage = z.infer<typeof WebviewMessageSchema>;
export function parseHostMessage(data: unknown): HostMessage | null { const result = HostMessageSchema.safeParse(data); return result.success ? result.data : null; }
export function parseWebviewMessage(data: unknown): WebviewMessage | null { const result = WebviewMessageSchema.safeParse(data); return result.success ? result.data : null; }
