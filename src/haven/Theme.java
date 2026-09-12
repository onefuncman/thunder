package haven;

public enum Theme {
    Pretty(DecoX.DecoThemeType.Big),
    Small(DecoX.DecoThemeType.Small),
    Ard(DecoX.DecoThemeType.Ard);
    
    public final DecoX.DecoThemeType deco;
    
    Theme(DecoX.DecoThemeType deco) {
        this.deco = deco;
    }

    public boolean usesArdHud() {
	return this == Ard;
    }

    public boolean usesFloatingHud() {
	return this == Ard;
    }
}
