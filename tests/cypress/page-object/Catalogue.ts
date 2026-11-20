interface CatalogueVersion {
    version: string
}

interface CatalogueModule {
    name: string
    versions: CatalogueVersion[]
}

/** The catalogue JSON of a site, which Jahia servers read to list and install the modules of the store. */
export class Catalogue {
    private readonly siteKey: string;

    constructor(siteKey: string) {
        this.siteKey = siteKey;
    }

    static of(siteKey: string): Catalogue {
        return new Catalogue(siteKey);
    }

    /** The entry of a module in the catalogue, under `module`; absent when the catalogue does not list it. */
    private module(name: string): Cypress.Chainable<{module?: CatalogueModule}> {
        return cy
            .request(`/cms/render/live/en/sites/${this.siteKey}/contents/modules-repository.moduleList.json`)
            .its('body')
            .then((repositories: {modules: CatalogueModule[]}[]) => {
                const modules: CatalogueModule[] = [];
                repositories.forEach(r => modules.push(...r.modules));
                const entry: {module?: CatalogueModule} = {module: modules.filter(m => m.name === name)[0]};
                return cy.wrap(entry, {log: false});
            }) as unknown as Cypress.Chainable<{module?: CatalogueModule}>;
    }

    /** The module lists exactly these versions, whatever their order. */
    expectVersions(name: string, versions: string[]): void {
        this.module(name).should(entry => {
            expect(entry.module, `the catalogue lists ${name}`).to.not.be.undefined;
            expect(entry.module.versions.map(v => v.version).sort()).to.deep.equal([...versions].sort());
        });
    }

    expectNoModule(name: string): void {
        this.module(name).should(entry => {
            expect(entry.module, `the catalogue lists ${name}`).to.be.undefined;
        });
    }
}
