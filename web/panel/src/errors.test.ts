import { describe, expect, it } from "vitest";
import { friendlyDetail, hasFriendlyDetail } from "../../shared/src/errors";

describe("friendlyDetail", () => {
  it("translates known server messages", () => {
    expect(friendlyDetail("Close or transfer active calls before ending the shift")).toMatch(/Encerre ou transfira/);
    expect(friendlyDetail("Request was already decided")).toBe("Este pedido já foi decidido.");
  });
  it("translates any 'not found' and 'too many rows' message", () => {
    expect(friendlyDetail("Reservation not found")).toMatch(/Não encontrado/);
    expect(friendlyDetail("Too many rows; narrow the period (limit 5000)")).toMatch(/reduza o período/);
  });
  it("keeps unknown messages as they came", () => {
    expect(friendlyDetail("Something new")).toBe("Something new");
    expect(hasFriendlyDetail("Something new")).toBe(false);
    expect(hasFriendlyDetail("Household limit reached")).toBe(true);
  });
});
