import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ProfilePage } from "./Profile";

const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200 });

describe("Perfil", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("a carga lenta do perfil não apaga o que a pessoa já digitou", async () => {
    let releaseProfile!: () => void;
    const profileReady = new Promise<void>((r) => (releaseProfile = r));
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      if (url.endsWith("/me/profile")) {
        await profileReady;
        return json({ fullName: "Fulano", email: "antigo@example.invalid", phone: "+55 11 90000-0000" });
      }
      if (url.endsWith("/me/preferences")) return json({ parcelsEnabled: true, visitorArrivalEnabled: true });
      return json({ items: [], page: 0, size: 20, totalElements: 0 });
    }));
    render(<ProfilePage token="t" condo="c" />);
    await userEvent.type(screen.getByLabelText("Telefone"), "+55 11 98888-0000");
    releaseProfile();
    await waitFor(() => expect(screen.getByText("Fulano")).toBeInTheDocument());
    expect(screen.getByLabelText("Telefone")).toHaveValue("+55 11 98888-0000"); // não voltou ao valor do servidor
    expect(screen.getByLabelText("E-mail")).toHaveValue("antigo@example.invalid"); // o campo intocado recebe o do servidor
  });
});
