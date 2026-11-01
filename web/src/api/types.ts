import type { components } from "./schema";

type Schemas = components["schemas"];

export type Company = Schemas["CompanyDto"];
export type Role = Schemas["RoleDto"];
export type Party = Schemas["PartyDto"];
export type CompanyEvent = Schemas["EventDto"];
export type SearchHit = Schemas["SearchHitDto"];
export type Network = Schemas["NetworkDto"];
export type NetworkNode = Schemas["NodeDto"];
export type NetworkEdge = Schemas["EdgeDto"];
export type Stats = Schemas["StatsDto"];
export type StatRow = Schemas["StatRowDto"];
export type RunSummary = Schemas["RunSummaryDto"];
export type Run = Schemas["RunDto"];
export type MassAddress = Schemas["MassAddressDto"];
