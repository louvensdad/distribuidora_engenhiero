package br.com.engenheiro.api.identityaccess.domain;

/** Classes de campo sensível de `contratos/permissoes.json`. Negadas sem concessão explícita. */
public enum FieldClass {
    OPERATIONAL_CONTACT("operational_contact"),
    DELIVERY_ADDRESS("delivery_address"),
    PERSONAL_DOCUMENT("personal_document"),
    EMPLOYEE_SALARY("employee_salary"),
    BANK_DATA("bank_data"),
    PURCHASE_COST("purchase_cost"),
    MARGIN("margin");

    private final String key;

    FieldClass(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static FieldClass fromKey(String key) {
        for (FieldClass f : values()) {
            if (f.key.equals(key)) {
                return f;
            }
        }
        throw new IllegalArgumentException("Classe de campo desconhecida: " + key);
    }
}
