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
