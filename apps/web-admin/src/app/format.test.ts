import { describe, expect, it } from "vitest";
import { toDecimal } from "./format";

describe("toDecimal (entrada pt-BR sem float)", () => {
  it("vírgula é decimal e ponto é milhar", () => {
    expect(toDecimal("1.234,5")).toBe("1234.5");
    expect(toDecimal("180,00")).toBe("180.00");
    expect(toDecimal("45.100,5")).toBe("45100.5");
  });
  it("sem vírgula: grupos de 3 após o ponto são milhar (odômetro 45.200 não vira 45,2)", () => {
    expect(toDecimal("45.200")).toBe("45200");
    expect(toDecimal("1.234.567")).toBe("1234567");
    expect(toDecimal("12.5")).toBe("12.5");
    expect(toDecimal("45200.5")).toBe("45200.5");
    expect(toDecimal("  ")).toBeNull();
  });
});

describe("datas no fuso de São Paulo", () => {
  it("às 22h30 de Brasília (01h30 UTC do dia seguinte) hoje ainda é o dia de Brasília", async () => {
    const { todayBr, monthStartBr } = await import("./format");
    const late = new Date("2026-11-30T01:30:00Z"); // 29/11 22:30 em São Paulo
    expect(todayBr(late)).toBe("2026-11-29");
    expect(monthStartBr(0, late)).toBe("2026-11-01");
    expect(monthStartBr(1, late)).toBe("2026-12-01");
    expect(monthStartBr(-11, late)).toBe("2025-12-01");
  });
});
