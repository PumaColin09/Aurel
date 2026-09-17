/*
 * AUREL — Capital Simulator 1.0
 * Copyright (c) 2026. Created for Colin. MIT license (see LICENSE).
 *
 * A complete, offline, single-player Java desktop game. No dependencies.
 * Compile: javac -encoding UTF-8 --release 17 Aurel.java
 * Run:     java Aurel
 * Or:      java -Dfile.encoding=UTF-8 Aurel.java
 * Tests:   java Aurel --self-test
 *
 * SOURCE MAP
 *  1. Entry point, presentation utilities
 *  2. Serializable domain model
 *  3. Market, brokerage, research and real-estate engine
 *  4. Versioned, checked, atomic persistence
 *  5. Custom Java2D desktop interface and interactive charts
 *  6. Built-in deterministic regression tests
 *
 * All companies, quotations, news, valuations and property offers are fictional.
 * The execution model is a simplified, finite-liquidity simulation, not a broker.
 */

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.zip.*;
import javax.imageio.ImageIO;

public class Aurel {
    static final String VERSION = "1.0.0";
    static final int SESSION = 102; // 09:00–17:30; one simulation tick = five minutes.
    static final int DAYS_YEAR = 252;
    static final int MAX_INTRADAY = 1530;
    static final String[] SECTORS = {"Technologie", "Halbleiter", "Gesundheit", "Energie",
        "Industrie", "Konsum", "Finanzen", "Infrastruktur"};
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd. MMM yyyy", Locale.GERMAN);
    static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("dd.MM.");
    static final DecimalFormat MONEY = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.GERMANY));
    static final DecimalFormat INTEGER = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.GERMANY));
    static final Path HOME = Paths.get(System.getProperty("user.home"), ".aurel");
    static final Path SAVE = HOME.resolve("career.aurel");
    static String fontFamily = "SansSerif";
    static final ConcurrentHashMap<String, Font> FONT_CACHE = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        Set<String> flags = new HashSet<>(Arrays.asList(args));
        if (flags.contains("--self-test")) { Tests.run(); return; }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("AUREL benötigt einen Desktop mit grafischer Oberfläche. Tests: --self-test");
            return;
        }
        for (String candidate : new String[]{"SF Pro Display", "SF Pro Text", "Segoe UI", "Inter", "Noto Sans", "SansSerif"}) {
            if (Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()).contains(candidate)) {
                fontFamily = candidate; break;
            }
        }
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "AUREL");
        UIManager.put("OptionPane.messageFont", font(14, false));
        UIManager.put("Button.font", font(14, true));
        UIManager.put("Label.font", font(14, false));
        boolean capture = flags.contains("--capture");
        Game initial = Game.create(20260917L, 100000);
        String notice = "Willkommen bei AUREL. Der Markt ist pausiert. Mit Leertaste startest du.";
        if (!capture && !flags.contains("--fresh") && Files.exists(SAVE)) {
            try { initial = Storage.load(SAVE); notice = "Dein Spielstand wurde geladen. Viel Erfolg."; }
            catch (Exception e) {
                notice = "Spielstand nicht lesbar. Neues Spiel; die defekte Datei bleibt erhalten.";
                // Do not silently overwrite a damaged save with a new game.
                try { Files.copy(SAVE, HOME.resolve("career-damaged-" + System.currentTimeMillis() + ".aurel")); }
                catch (IOException ignored) { }
            }
        }
        final Game game = initial;
        final String welcome = notice;
        SwingUtilities.invokeLater(() -> {
            Window window = new Window(game, capture);
            window.showToast(welcome);
            window.setVisible(true);
            if (capture) window.captureScreens();
        });
    }

    static Font font(float size, boolean bold) {
        String key=fontFamily+":"+Math.round(size)+":"+bold;
        return FONT_CACHE.computeIfAbsent(key,k->new Font(fontFamily,bold?Font.BOLD:Font.PLAIN,Math.round(size)));
    }
    static Font displayFont(String text,float size,boolean bold) {
        Font f=font(size,bold);
        if(f.canDisplayUpTo(text)==-1)return f;
        String key="Dialog:"+Math.round(size)+":"+bold;
        return FONT_CACHE.computeIfAbsent(key,k->new Font("Dialog",bold?Font.BOLD:Font.PLAIN,Math.round(size)));
    }
    static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    static double cents(double v) { return Math.round(v * 100.0) / 100.0; }
    static String eur(double v) { return MONEY.format(v) + " €"; }
    static String num(double v) { return MONEY.format(v); }
    static String pct(double v) { return String.format(Locale.GERMANY, "%+.2f %%", v * 100); }
    static String plainPct(double v) { return String.format(Locale.GERMANY, "%.1f %%", v * 100); }
    static String compact(double v) {
        if (Math.abs(v) >= 1e9) return String.format(Locale.GERMANY, "%.2f Mrd.", v / 1e9);
        if (Math.abs(v) >= 1e6) return String.format(Locale.GERMANY, "%.2f Mio.", v / 1e6);
        if (Math.abs(v) >= 10000) return String.format(Locale.GERMANY, "%.1f Tsd.", v / 1000);
        return INTEGER.format(v);
    }
    static LocalDate date(int day) {
        LocalDate start = LocalDate.of(2027, 1, 4);
        int weeks = Math.floorDiv(day, 5), rem = Math.floorMod(day, 5);
        return start.plusWeeks(weeks).plusDays(rem);
    }
    static String time(int slot) {
        int minutes = 540 + slot * 5;
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }
    static int eventDay(int tick) { return tick > 0 && tick % SESSION == 0 ? tick / SESSION - 1 : Math.floorDiv(tick, SESSION); }
    static int eventSlot(int tick) { return tick > 0 && tick % SESSION == 0 ? SESSION : Math.floorMod(tick, SESSION); }
    static String timestamp(int tick) {
        return date(eventDay(tick)).format(SHORT_DATE) + " " + time(eventSlot(tick));
    }

    // ───────────────────── 2. DOMAIN MODEL ──────────────────────────────────

    /** A small, reproducible random generator. Its complete state lives in the save. */
    static class Rng implements Serializable {
        private static final long serialVersionUID = 1L;
        long state;
        Rng(long seed) { state = seed == 0 ? 0x5deece66dL : seed; }
        long nextLong() { long x = state; x ^= x >>> 12; x ^= x << 25; x ^= x >>> 27; state = x; return x * 2685821657736338717L; }
        double next() { return (nextLong() >>> 11) * 0x1.0p-53; }
        int integer(int n) { return (int)(next() * n); }
        double gaussian() { return Math.sqrt(-2 * Math.log(Math.max(1e-14, next()))) * Math.cos(2 * Math.PI * next()); }
    }

    static class Candle implements Serializable {
        private static final long serialVersionUID = 1L;
        int day, slot;
        double open, high, low, close;
        long volume;
        Candle(int d, int s, double o, double h, double l, double c, long v) {
            day = d; slot = s; open = o; high = Math.max(h, Math.max(o,c));
            low = Math.min(l, Math.min(o,c)); close = c; volume = v;
        }
        Candle copy() { return new Candle(day,slot,open,high,low,close,volume); }
    }

    static class Stock implements Serializable {
        private static final long serialVersionUID = 1L;
        String symbol, name, description;
        int sector, color, liquidity, research, reportDay, analystDay = -1;
        double price, previousClose, annualVol, beta, annualYield, eps, growth, debtRatio;
        double fairValue, momentum, newsDrift, analystValue, analystUncertainty, shares, initialPrice;
        long averageVolume, sessionVolume;
        ArrayList<Candle> daily = new ArrayList<>(), intraday = new ArrayList<>();
        Stock(String sy, String n, int se, double p, double vol, double y, double size, int co, String desc) {
            symbol=sy; name=n; sector=se; price=p; annualVol=vol; annualYield=y; shares=size;
            color=co; description=desc; beta=.65+vol*1.5; averageVolume=(long)(size*.009);
        }
        double change() { return price / previousClose - 1; }
        double spread(Game g) { return Math.max(.02, price * (.00032 + annualVol*.0011) * (g.regime == 2 ? 2 : 1)); }
        double bid(Game g) { return Math.max(.01, cents(price - spread(g)/2)); }
        double ask(Game g) { return cents(price + spread(g)/2); }
        double marketCap() { return shares*price; }
        double pe() { return eps <= 0 ? 0 : price/eps; }
        Candle last() { return intraday.get(intraday.size()-1); }
    }

    static class Position implements Serializable {
        private static final long serialVersionUID = 1L;
        int quantity;
        double average;
    }

    static class Order implements Serializable {
        private static final long serialVersionUID = 1L;
        long id;
        String symbol, type, tif, status="OFFEN", reason="";
        boolean buy, activated;
        int quantity, remaining, createdTick;
        double limit, stop, trail, peak, value, fees;
        boolean active() { return status.equals("OFFEN") || status.equals("TEILWEISE"); }
        int filled() { return quantity-remaining; }
        double average() { return filled()==0 ? 0 : value/filled(); }
    }

    static class Fill implements Serializable {
        private static final long serialVersionUID = 1L;
        int tick, quantity;
        String symbol;
        boolean buy;
        double price, fee, realized;
        long orderId;
        Fill(int t, String s, boolean b, int q, double p, double f, double r, long id) {
            tick=t;symbol=s;buy=b;quantity=q;price=p;fee=f;realized=r;orderId=id;
        }
    }

    static class News implements Serializable {
        private static final long serialVersionUID = 1L;
        int tick, sentiment;
        String category, title, body, symbol;
        News(int t, String c, String h, String b, String s, int sign) {tick=t;category=c;title=h;body=b;symbol=s;sentiment=sign;}
    }

    static class Research implements Serializable {
        private static final long serialVersionUID = 1L;
        String key, title;
        int start, end, level;
        double cost;
        Research(String k,String t,int a,int b,int l,double c){key=k;title=t;start=a;end=b;level=l;cost=c;}
    }

    static class Venture implements Serializable {
        private static final long serialVersionUID = 1L;
        String id,name,stage,sector,description;
        double ticket, valuation, failure;
        int duration, requirement, color;
        Venture(String id,String n,String s,String sec,String d,double t,double v,double f,int dur,int req,int c){
            this.id=id;name=n;stage=s;sector=sec;description=d;ticket=t;valuation=v;failure=f;duration=dur;requirement=req;color=c;
        }
    }

    static class Stake implements Serializable {
        private static final long serialVersionUID = 1L;
        String ventureId,name,status="GEBUNDEN";
        double invested, mark, ownership, payout, failure;
        int startDay, exitDay;
        boolean active(){return status.equals("GEBUNDEN");}
    }

    static class PropertyOffer implements Serializable {
        private static final long serialVersionUID = 1L;
        String id,name,location,description;
        double price,yield,occupancy;
        int color;
        PropertyOffer(String id,String n,String loc,String desc,double p,double y,double o,int c){
            this.id=id;name=n;location=loc;description=desc;price=p;yield=y;occupancy=o;color=c;
        }
    }

    static class Property implements Serializable {
        private static final long serialVersionUID = 1L;
        String id,name;
        double value,debt,rate,payment,basis,rentYield,occupancy,income;
        int level,boughtDay;
    }

    static class PriceAlert implements Serializable {
        private static final long serialVersionUID = 1L;
        String symbol;
        double target;
        boolean up,active=true;
    }

    static class EquityPoint implements Serializable {
        private static final long serialVersionUID = 1L;
        int tick;
        double wealth,benchmark;
        EquityPoint(int t,double w,double b){tick=t;wealth=w;benchmark=b;}
    }

    static class Ledger implements Serializable {
        private static final long serialVersionUID = 1L;
        int tick;
        String kind, detail;
        double amount;
        Ledger(int t,String k,String d,double a){tick=t;kind=k;detail=d;amount=a;}
    }

    // ───────────────────── 3. GAME / MARKET ENGINE ───────────────────────────

    static class Game implements Serializable {
        private static final long serialVersionUID = 1L;
        LinkedHashMap<String,Stock> stocks = new LinkedHashMap<>();
        LinkedHashMap<String,Position> positions = new LinkedHashMap<>();
        ArrayList<Order> orders = new ArrayList<>();
        ArrayList<Fill> fills = new ArrayList<>();
        ArrayList<News> news = new ArrayList<>();
        ArrayList<Research> projects = new ArrayList<>();
        ArrayList<Venture> ventures = new ArrayList<>();
        ArrayList<Stake> stakes = new ArrayList<>();
        ArrayList<PropertyOffer> propertyOffers = new ArrayList<>();
        ArrayList<Property> properties = new ArrayList<>();
        ArrayList<PriceAlert> alerts = new ArrayList<>();
        ArrayList<EquityPoint> equity = new ArrayList<>();
        ArrayList<Ledger> ledger = new ArrayList<>();
        HashSet<String> watchlist = new HashSet<>(), achievements = new HashSet<>();
        HashMap<String,Integer> upgrades = new HashMap<>();
        Rng rng;
        long seed,nextOrder=1;
        int day,slot=62,regime, researchCompleted,privateExits;
        double cash,initialCapital,realized,dividends,interest,totalFees,privateProfit,propertyProfit;
        double rate=.035,inflation=.027,economy=.018,marketMomentum,peakWealth,maxDrawdown;
        boolean light=false,animation=true;
        transient ArrayList<String> notifications = new ArrayList<>();

        static Game create(long seed, double capital) {
            Game g=new Game();g.seed=seed;g.rng=new Rng(seed);g.cash=capital;g.initialCapital=capital;g.peakWealth=capital;
            g.populate();g.seedHistory();
            g.addNews("BRIEFING","Deine neue Ära beginnt.","Du startest mit "+eur(capital)+". Alle Kurse und Unternehmen sind simuliert. Investiere, erforsche Unternehmen und baue langfristig Vermögen auf.","",0);
            g.addNews("KONJUNKTUR","Stabiler Auftakt, vorsichtiger Optimismus.","Der Leitzins liegt bei 3,5 %. Technologie und Infrastruktur stehen im Fokus. Neue Unternehmenszahlen können die Erwartungen verändern.","",0);
            g.sampleEquity();return g;
        }

        void populate() {
            add("NOVA","Nova Systems",0,86.40,.39,.004,820e6,0x9CA9FF,"Cloud-Infrastruktur und Unternehmenssoftware. Wiederkehrende Umsätze, aber sensibel für hohe Zinsen.");
            add("VELA","Vela Software",0,142.80,.32,.008,310e6,0xCFA9E7,"Sicherheitssoftware für Unternehmen mit hohen Abonnementerlösen und intensivem Wettbewerb.");
            add("ORBT","Orbit Networks",0,38.20,.49,0,180e6,0xA3C8EB,"Satellitengestützte Datennetze. Hohe Investitionen treffen auf starkes, unsicheres Wachstum.");
            add("SILX","Silix Semiconductor",1,176.50,.45,.006,1150e6,0xABCEC8,"Rechenchips für KI und Rechenzentren. Stark zyklisch und von Lieferketten abhängig.");
            add("QNTM","Quantum Foundry",1,62.70,.56,0,220e6,0xC3B2F8,"Fortschrittliche Chipfertigung. Hoher Kapitalbedarf und starke Reaktion auf Auftragseingänge.");
            add("CORE","Core Devices",1,94.30,.30,.019,430e6,0x8BBEBA,"Halbleiter für Industrie und Fahrzeuge mit breitem Kundenstamm und solider Dividende.");
            add("HELX","Helix Therapeutics",2,57.80,.52,0,155e6,0xDFB4C1,"Biotechnologie mit klinischen Programmen. Studiendaten können zu ausgeprägten Kurssprüngen führen.");
            add("VITA","Vita Medical",2,118.40,.23,.024,290e6,0xB7CBA1,"Medizintechnik und Diagnostik. Vergleichsweise defensive Nachfrage und laufende Forschungsaufwendungen.");
            add("GENE","Genova Labs",2,29.60,.61,0,90e6,0xC2AFE5,"Genomdiagnostik in der Wachstumsphase. Chancen durch Zulassungen, Risiko durch Finanzierung.");
            add("SOLR","Solara Energy",3,44.80,.42,.012,420e6,0xE4CE92,"Solarparks, Netzdienstleistungen und Batteriespeicher. Zins- und rohstoffabhängiges Projektgeschäft.");
            add("NRGY","North Energy",3,72.60,.29,.047,920e6,0xCAA88B,"Diversifizierter Energieversorger. Hoher laufender Cashflow, regulatorische und Rohstoffrisiken.");
            add("HYDR","Hydron Power",3,18.90,.65,0,125e6,0x99C5D7,"Wasserstoffanlagen im frühen kommerziellen Einsatz. Hohe Volatilität und Kapitalbedarf.");
            add("AXIS","Axis Robotics",4,98.20,.35,.013,210e6,0xA9BDC9,"Automatisierung und Fertigungsroboter für globale Industriekunden. Konjunktursensibles Orderbuch.");
            add("AERO","Aero Dynamics",4,163.50,.28,.021,310e6,0xAABDDC,"Luftfahrtkomponenten und Wartung. Langfristige Verträge, aber hohe Fixkosten.");
            add("FRTG","Freight Global",4,48.30,.31,.033,230e6,0xD7BD99,"Logistik und Gütertransport. Treibstoffkosten und weltweiter Handel bestimmen die Margen.");
            add("LUMA","Luma Consumer",5,212.70,.26,.011,940e6,0xDDB6A9,"Premium-Elektronik mit eigener Softwareplattform. Starke Marke, abhängig von Produktzyklen.");
            add("ATLR","Atelier Group",5,134.60,.29,.018,280e6,0xD4C2A7,"Luxus- und Lifestylemarken. Preissetzungsmacht, jedoch empfindlich bei schwacher Konsumstimmung.");
            add("FRES","Fresh Markets",5,35.90,.19,.028,190e6,0xB5C9A0,"Lebensmittelhandel mit defensiven Umsätzen und niedrigen, stabilen Margen.");
            add("ARCO","Arco Financial",6,64.20,.28,.041,670e6,0xA3B8D8,"Universalbank. Zinsmargen, Kreditqualität und Konjunktur prägen das Geschäftsmodell.");
            add("FLOW","Flow Payments",6,82.10,.41,.003,340e6,0xC5AFE8,"Digitale Zahlungsabwicklung. Skalierbares Wachstum bei hoher regulatorischer Konkurrenz.");
            add("SAFE","Safeguard Insurance",6,156.30,.22,.039,210e6,0xB1C9C6,"Versicherungsgruppe mit Kapitalanlagegeschäft. Risiken aus Schadensereignissen und Finanzmärkten.");
            add("GRID","Grid Infrastructure",7,52.40,.22,.035,480e6,0xB9C9A4,"Stromnetze und kritische Infrastruktur. Planbare Erlöse bei langfristigem Kapitalbedarf.");
            add("PORT","Portside Logistics",7,76.80,.26,.027,250e6,0xACBCC8,"Häfen und Logistikimmobilien. Profitiert von Handelsvolumen und stabilen Pachtverträgen.");
            add("TOWR","Tower Connect",7,102.50,.25,.031,360e6,0xC5BFD4,"Mobilfunkmasten und Datencenter-Standorte. Wiederkehrende Mieten, zinssensitive Finanzierung.");
            watchlist.addAll(Arrays.asList("NOVA","SILX","LUMA","VITA","AXIS","SOLR"));
            ventures.add(new Venture("moss","Moss Materials","Seed","Climate Tech","Biobasierte Verpackungen für die Industrie. Noch vor dem profitablen Großserienbetrieb.",2500,1.2e6,.42,42,0,0xB8CEA5));
            ventures.add(new Venture("pulse","Pulse Robotics","Series A","Robotik","Kollaborative Lagerroboter mit ersten Großkunden. Wachstum benötigt frisches Kapital.",7500,8e6,.29,63,0,0xB5B9EF));
            ventures.add(new Venture("arc","Arc Fusion","Seed","Deep Tech","Neue Komponenten für Fusionsanlagen. Technologischer Durchbruch oder kompletter Ausfall.",5000,3e6,.61,84,1,0xD5B1D3));
            ventures.add(new Venture("cove","Cove Health","Series A","Digital Health","Diagnostiksoftware für Kliniken. Zulassung und Kostenerstattung sind entscheidend.",12500,14e6,.34,70,1,0xA6C7D3));
            ventures.add(new Venture("atlas","Atlas Storage","Growth","Energie","Modulare Batteriespeicher mit laufenden Umsätzen und internationalen Pilotkunden.",25000,45e6,.20,105,2,0xD8C196));
            ventures.add(new Venture("lattice","Lattice Compute","Series B","Halbleiter","Optische Interconnects für Rechenzentren. Ein kapitalintensiver Angriff auf etablierte Anbieter.",50000,80e6,.36,126,3,0xB4ACDB));
            propertyOffers.add(new PropertyOffer("park","Urban Parking","Nordhafen · Stellplatz","Ein vermieteter Tiefgaragenplatz mit überschaubarem Kapitalbedarf.",18000,.065,.94,0xA9BED4));
            propertyOffers.add(new PropertyOffer("loft","Studio Twenty","Westend · 31 m²","Kompaktes Studio nahe dem Universitätsviertel mit regelmäßiger Nachfrage.",84000,.057,.94,0xB6B4A2));
            propertyOffers.add(new PropertyOffer("duo","Parkside Residence","Parkviertel · 68 m²","Zweizimmerwohnung mit Balkon und gutem Vermietungspotenzial.",215000,.049,.96,0xB0C2AC));
            propertyOffers.add(new PropertyOffer("office","The Junction","City-West · 180 m²","Kleine Bürofläche für junge Unternehmen. Mehr Ertrag, aber erhöhtes Leerstandsrisiko.",410000,.071,.86,0xB5AED0));
            propertyOffers.add(new PropertyOffer("house","Riverside House","Uferquartier · 6 Einheiten","Mehrfamilienhaus mit diversifizierten Mietverhältnissen und höherem Verwaltungsaufwand.",890000,.055,.95,0xC7B29F));
            propertyOffers.add(new PropertyOffer("hub","Logistics One","Gewerbepark · 1.200 m²","Logistikhalle mit langfristigen Mietern. Konjunkturabhängiger Standort.",1650000,.068,.91,0xA6C0C3));
        }

        void add(String sy,String n,int se,double p,double vol,double y,double shares,int co,String desc) {
            Stock s=new Stock(sy,n,se,p,vol,y,shares,co,desc);
            s.growth=.03+rng.next()*.19;s.debtRatio=.15+rng.next()*.6;
            s.eps=p/(13+rng.next()*25);s.fairValue=p*(.9+rng.next()*.25);s.reportDay=3+rng.integer(38);
            stocks.put(sy,s);
        }

        /** Historical candles are generated forwards, with internally consistent OHLC values. */
        void seedHistory() {
            for(Stock s:stocks.values()) {
                double p=s.price*.78;
                for(int d=-190;d<-7;d++) {
                    double o=p, drift=(s.symbol.equals("NOVA")&&d>-78) ? .004 : .00065;
                    p=Math.max(2,p*Math.exp(drift+s.annualVol/Math.sqrt(252)*rng.gaussian()));
                    double wiggle=Math.abs(rng.gaussian())*.007;
                    s.daily.add(new Candle(d,SESSION,o,Math.max(o,p)*(1+wiggle),Math.min(o,p)/(1+wiggle),p,(long)(s.averageVolume*(.65+rng.next()*.8))));
                }
                for(int d=-7;d<=0;d++) {
                    double o=p;long v=0;double h=p,l=p;
                    int count=d==0?slot:SESSION;
                    p=Math.max(1,p*Math.exp(rng.gaussian()*.004));
                    for(int j=1;j<=count;j++) {
                        double start=p;
                        double drift=s.symbol.equals("NOVA")?.00009:.000004;
                        p=Math.max(1,p*Math.exp(drift+s.annualVol/Math.sqrt(252*SESSION)*rng.gaussian()));
                        double wiggle=Math.abs(rng.gaussian())*.00075;
                        long volume=(long)(s.averageVolume/(double)SESSION*(.4+rng.next()*1.2));
                        Candle c=new Candle(d,j,start,Math.max(start,p)*(1+wiggle),Math.min(start,p)/(1+wiggle),p,volume);
                        s.intraday.add(c);h=Math.max(h,c.high);l=Math.min(l,c.low);v+=volume;
                    }
                    s.daily.add(new Candle(d,count,o,h,l,p,v));
                    if(d==-1)s.previousClose=p;
                    if(d==0)s.sessionVolume=v;
                }
                s.price=cents(p);s.last().close=s.price;
                s.last().high=Math.max(s.last().high,s.price);s.last().low=Math.min(s.last().low,s.price);
                Candle daily=s.daily.get(s.daily.size()-1);daily.close=s.price;
                daily.high=Math.max(daily.high,s.price);daily.low=Math.min(daily.low,s.price);
                s.initialPrice=s.price;s.fairValue=s.price*(.86+rng.next()*.32);
                s.eps=s.price/(14+rng.next()*26);
                s.liquidity=Math.max(50,(int)(s.averageVolume/SESSION*.028));
            }
        }

        int tick(){return day*SESSION+slot;}
        String clock(){return date(day).format(DATE)+"  ·  "+time(slot);}
        String regimeName(){return new String[]{"Expansion","Abkühlung","Risiko-Off","Erholung"}[regime];}
        int upgrade(String k){return upgrades.getOrDefault(k,0);}
        Position position(String sy){return positions.computeIfAbsent(sy,k->new Position());}
        int held(String sy){Position p=positions.get(sy);return p==null?0:p.quantity;}
        double index(){double v=0;for(Stock s:stocks.values())v+=s.price/s.initialPrice;return 1000*v/stocks.size();}
        double stockValue(){double v=0;for(Map.Entry<String,Position> e:positions.entrySet())v+=e.getValue().quantity*stocks.get(e.getKey()).price;return v;}
        double privateValue(){return stakes.stream().filter(Stake::active).mapToDouble(s->s.mark).sum();}
        double propertyValue(){return properties.stream().mapToDouble(p->p.value-p.debt).sum();}
        double totalDebt(){return properties.stream().mapToDouble(p->p.debt).sum();}
        double wealth(){return cash+stockValue()+privateValue()+propertyValue();}
        double unrealized(){double v=0;for(Map.Entry<String,Position> e:positions.entrySet())v+=e.getValue().quantity*(stocks.get(e.getKey()).price-e.getValue().average);return v;}
        double feeRate(){return .001*(1-.20*upgrade("routing"));}
        double fee(double notional, boolean first){return cents((first?.95:0)+notional*feeRate());}
        double reserve(Order o){
            if(!o.active()||!o.buy)return 0;
            Stock s=stocks.get(o.symbol);
            double cap=(o.type.equals("LIMIT")||o.type.equals("STOP-LIMIT"))?o.limit:Math.max(s.ask(this),o.stop)*1.04;
            return cents(cap*o.remaining+fee(cap*o.remaining,o.filled()==0));
        }
        double reservedCash(){return orders.stream().mapToDouble(this::reserve).sum();}
        double freeCash(){return Math.max(0,cash-reservedCash());}
        int reservedShares(String sy){return orders.stream().filter(o->o.active()&&!o.buy&&o.symbol.equals(sy)).mapToInt(o->o.remaining).sum();}
        int availableShares(String sy){return held(sy)-reservedShares(sy);}
        int activeOrders(){return (int)orders.stream().filter(Order::active).count();}
        double markedAnnualRent(){return properties.stream().mapToDouble(p->p.value*(p.rentYield*p.occupancy-.01*(1-.15*upgrade("estate")))).sum();}
        double monthlyMortgage(){return properties.stream().mapToDouble(p->p.debt>0?p.payment:0).sum();}

        void notifyPlayer(String s){if(notifications==null)notifications=new ArrayList<>();notifications.add(s);if(notifications.size()>30)notifications.remove(0);}
        void addNews(String category,String title,String body,String symbol,int sign){
            news.add(0,new News(tick()+(slot==0?1:0),category,title,body,symbol,sign));while(news.size()>180)news.remove(news.size()-1);
        }
        void cashflow(String kind,String detail,double amount){
            ledger.add(0,new Ledger(tick(),kind,detail,cents(amount)));while(ledger.size()>3000)ledger.remove(ledger.size()-1);
        }

        /** One causal five-minute step. Orders see the completed quote, never a past bar's high/low. */
        void step() {
            if(slot>=SESSION)openNextDay();
            slot++;
            double stress=regime==2?1.8:1;
            double macro=(new double[]{.000008,-.000007,-.000018,.000014}[regime]);
            double common=rng.gaussian()*.00070*stress+marketMomentum*.12+macro;
            marketMomentum=common;
            double[] sectorMoves=new double[SECTORS.length];
            for(int i=0;i<sectorMoves.length;i++)sectorMoves[i]=rng.gaussian()*.00045*stress;
            if(rng.next()<.010)randomNews();
            for(Stock s:stocks.values()) {
                double old=s.price;
                double ret=common*s.beta+sectorMoves[s.sector]+s.annualVol/Math.sqrt(252.0*SESSION)*rng.gaussian()*stress;
                ret+=s.momentum*.075+Math.log(s.fairValue/old)*.000016+s.newsDrift;
                s.newsDrift*=.67;s.momentum=clamp(ret,-.045,.045);
                s.price=Math.max(.10,cents(old*Math.exp(clamp(ret,-.12,.12))));
                double wick=Math.abs(rng.gaussian())*s.annualVol/Math.sqrt(252.0*SESSION)*.32;
                long volume=(long)(s.averageVolume/(double)SESSION*(.45+rng.next())*(1+Math.abs(ret)*110)*(slot<8||slot>95?1.8:1));
                Candle c=new Candle(day,slot,old,Math.max(old,s.price)*(1+wick),Math.min(old,s.price)/(1+wick),s.price,volume);
                s.intraday.add(c);if(s.intraday.size()>MAX_INTRADAY)s.intraday.remove(0);
                s.sessionVolume+=volume;
                Candle d=s.daily.get(s.daily.size()-1);
                if(d.day!=day){d=new Candle(day,slot,old,c.high,c.low,s.price,volume);s.daily.add(d);}
                else {d.high=Math.max(d.high,c.high);d.low=Math.min(d.low,c.low);d.close=s.price;d.slot=slot;d.volume+=volume;}
                if(s.daily.size()>520)s.daily.remove(0);
                s.liquidity=Math.max(5,(int)(volume*.028));
                checkAlerts(s,old);
            }
            processOrders();finishResearch();
            if(slot==SESSION)closeDay();
            if(slot%6==0||slot==SESSION)sampleEquity();
            checkAchievements();
        }

        void advance(int ticks){for(int i=0;i<ticks;i++)step();}
        void sampleEquity(){
            double w=wealth();peakWealth=Math.max(peakWealth,w);maxDrawdown=Math.max(maxDrawdown,1-w/Math.max(1,peakWealth));
            equity.add(new EquityPoint(tick(),w,index()/1000*initialCapital));if(equity.size()>6500)equity.remove(0);
        }

        void openNextDay(){
            day++;slot=0;
            if(day%21==0){
                inflation=clamp(inflation+rng.gaussian()*.0015,.002,.095);
                economy=clamp(economy+rng.gaussian()*.003,-.04,.07);
                rate=clamp(rate+(inflation>.032?.0025:inflation<.023?-.0025:0),.0025,.10);
                if(rng.next()<.55)regime=rng.integer(4);
                addNews("MAKRO","Zinsentscheid: "+plainPct(rate)+" Leitzins.","Inflation: "+plainPct(inflation)+" · Wachstum: "+plainPct(economy)+". Das Marktumfeld wechselt zu "+regimeName()+".","",rate>.04?-1:0);
            }
            for(Stock s:stocks.values()){
                s.previousClose=s.price;s.sessionVolume=0;
                double gap=rng.gaussian()*s.annualVol/Math.sqrt(252)*.22;
                s.price=Math.max(.10,cents(s.price*Math.exp(gap)));
                if(day==s.reportDay)earnings(s);
                int offset=Math.abs(s.symbol.hashCode())%63;
                if(day%63==offset&&s.annualYield>0){
                    double per=cents(s.previousClose*s.annualYield/4);
                    double paid=cents(held(s.symbol)*per);
                    if(paid>0){cash=cents(cash+paid);dividends+=paid;cashflow("DIVIDENDE",s.symbol+" · "+eur(per)+" / Aktie",paid);notifyPlayer(s.symbol+": "+eur(paid)+" Dividende erhalten.");}
                    s.price=Math.max(.10,cents(s.price-per));s.fairValue=Math.max(.10,s.fairValue-per);
                    addNews("DIVIDENDE",s.symbol+" notiert ex Dividende.","Ausschüttung: "+eur(per)+" je gehaltener Aktie. Der Kurs wurde um die Ausschüttung vermindert.",s.symbol,0);
                }
                s.fairValue*=Math.exp((.035+s.growth*.12)/252+rng.gaussian()*.0015);
                // The open-to-first-tick candle includes the opening gap through its open.
                s.daily.add(new Candle(day,0,s.price,s.price,s.price,s.price,0));
            }
        }

        void earnings(Stock s){
            double surprise=rng.gaussian()*.105;
            s.eps=Math.max(.01,s.eps*(1+s.growth/4+surprise));
            s.growth=clamp(s.growth+rng.gaussian()*.025,-.16,.42);
            s.debtRatio=clamp(s.debtRatio+rng.gaussian()*.025,.02,.95);
            s.fairValue*=Math.exp(surprise*.6);
            s.price=Math.max(.10,cents(s.price*Math.exp(clamp(surprise*.40,-.23,.23))));
            s.reportDay=day+63;
            addNews("QUARTALSZAHLEN",s.name+": "+(surprise>=0?"über":"unter")+" den Erwartungen.",
                "Gewinnüberraschung: "+pct(surprise)+". Gewinn je Aktie: "+eur(s.eps)+"; Umsatzwachstum: "+plainPct(s.growth)+". Der Markt verarbeitet die neue Bewertung.",s.symbol,surprise>=0?1:-1);
            if(watchlist.contains(s.symbol)||held(s.symbol)>0)notifyPlayer("Neue Quartalszahlen: "+s.name);
        }

        void randomNews(){
            Stock s=new ArrayList<>(stocks.values()).get(rng.integer(stocks.size()));
            boolean positive=rng.next()>.48;
            String[] good={"Großauftrag stärkt den Ausblick","Neue Partnerschaft angekündigt","Nachfrage übertrifft die Planung","Effizienzprogramm zeigt Wirkung","Produktstart trifft auf hohe Nachfrage"};
            String[] bad={"Lieferverzögerungen belasten das Geschäft","Kosten steigen stärker als erwartet","Ausblick vorsichtiger formuliert","Konkurrent erhöht den Preisdruck","Produktprojekt verzögert sich"};
            double impact=(.002+rng.next()*.010)*(positive?1:-1);
            s.newsDrift+=impact;s.fairValue*=Math.exp(impact*2.0);
            addNews("UNTERNEHMEN",s.name+": "+(positive?good[rng.integer(good.length)]:bad[rng.integer(bad.length)])+".",
                "Die Meldung verändert kurzfristig die Nachfrage und die längerfristige Bewertung. Richtung und Stärke der Kursreaktion bleiben unsicher.",s.symbol,positive?1:-1);
        }

        void closeDay(){
            for(Order o:orders)if(o.active()&&o.tif.equals("DAY")){o.status="ABGELAUFEN";o.reason="Handelsschluss";}
            double earned=cents(cash*Math.expm1(Math.log1p(Math.max(.0025,rate-.01))/252));
            cash=cents(cash+earned);interest+=earned;if(earned>0)cashflow("ZINSEN","Tageszins auf Kontoguthaben",earned);
            updatePrivate();updateProperties();
        }

        Order submit(String symbol, boolean buy, String type, int quantity, double limit, double stop, double trail, String tif){
            if(!stocks.containsKey(symbol))throw new IllegalArgumentException("Unbekanntes Wertpapier.");
            if(!Set.of("MARKET","LIMIT","STOP","STOP-LIMIT","TRAILING").contains(type))throw new IllegalArgumentException("Unbekannter Ordertyp.");
            if(!Set.of("DAY","GTC").contains(tif))throw new IllegalArgumentException("Ungültige Gültigkeit.");
            if(slot>=SESSION)throw new IllegalArgumentException("Die Börse ist geschlossen. Starte den nächsten Handelstag.");
            if(quantity<1||quantity>10000000)throw new IllegalArgumentException("Die Stückzahl muss zwischen 1 und 10.000.000 liegen.");
            if(!Double.isFinite(limit)||!Double.isFinite(stop)||!Double.isFinite(trail))throw new IllegalArgumentException("Bitte gültige Zahlen eingeben.");
            if((type.equals("LIMIT")||type.equals("STOP-LIMIT"))&&limit<.01)throw new IllegalArgumentException("Der Limitpreis muss positiv sein.");
            if((type.equals("STOP")||type.equals("STOP-LIMIT"))&&stop<.01)throw new IllegalArgumentException("Der Stoppreis muss positiv sein.");
            if(type.equals("TRAILING")&&(buy||trail<.001||trail>.5))throw new IllegalArgumentException("Trailing-Stop: nur Verkauf; Abstand 0,1 bis 50 %.");
            if(activeOrders()>=80)throw new IllegalArgumentException("Maximal 80 offene Orders.");
            Order o=new Order();o.id=nextOrder;o.symbol=symbol;o.buy=buy;o.type=type;o.quantity=quantity;o.remaining=quantity;
            o.limit=cents(limit);o.stop=cents(stop);o.trail=trail;o.peak=stocks.get(symbol).price;o.createdTick=tick();o.tif=tif;
            if(type.equals("TRAILING"))o.stop=cents(o.peak*(1-trail));
            if(buy&&reserve(o)>freeCash()+.001)throw new IllegalArgumentException("Nicht genügend freies Guthaben. Benötigt inklusive Reserve: "+eur(reserve(o))+".");
            if(!buy&&quantity>availableShares(symbol))throw new IllegalArgumentException("Nicht genügend unreservierte Aktien. Verfügbar: "+availableShares(symbol)+".");
            nextOrder++;orders.add(0,o);processOrders();checkAchievements();
            // Retain all active orders, but cap completed-order history.
            if(orders.size()>2500){for(int i=orders.size()-1;i>=0&&orders.size()>2200;i--)if(!orders.get(i).active())orders.remove(i);}
            return o;
        }

        double executionPrice(Stock s,boolean buy,int quantity){
            double impact=s.price*Math.min(.022, quantity/Math.max(1.0,s.averageVolume)*.38);
            double quote=buy?s.ask(this)+impact:s.bid(this)-impact;
            // Conservative tick rounding prevents a fractional-cent limit violation.
            return Math.max(.01,(buy?Math.ceil(quote*100-1e-8):Math.floor(quote*100+1e-8))/100.0);
        }
        boolean withinLimit(Order o,double p){
            boolean limited=o.type.equals("LIMIT")||o.type.equals("STOP-LIMIT");
            return !limited||(o.buy?p<=o.limit+1e-9:p>=o.limit-1e-9);
        }

        void processOrders(){
            // Price-time approximation: earlier submitted orders consume liquidity first.
            for(int i=orders.size()-1;i>=0;i--){
                Order o=orders.get(i);if(!o.active())continue;Stock s=stocks.get(o.symbol);
                if(o.type.equals("TRAILING")&&!o.activated){o.peak=Math.max(o.peak,s.price);o.stop=cents(o.peak*(1-o.trail));}
                boolean conditional=o.type.equals("STOP")||o.type.equals("STOP-LIMIT")||o.type.equals("TRAILING");
                if(conditional&&!o.activated){
                    if(o.buy?s.price>=o.stop:s.price<=o.stop){o.activated=true;notifyPlayer(s.symbol+": Stop ausgelöst.");}
                    else continue;
                }
                int amount=Math.min(o.remaining,s.liquidity);if(amount<=0)continue;
                if(!o.buy)amount=Math.min(amount,held(o.symbol));
                double funds=Math.max(0,cash-(reservedCash()-reserve(o)));
                // Binary search largest fill satisfying both cash and limit constraints.
                int low=0,high=amount;
                while(low<high){
                    int mid=low+(high-low+1)/2;double p=executionPrice(s,o.buy,mid);
                    boolean valid=withinLimit(o,p)&&(!o.buy||cents(mid*p+fee(mid*p,o.filled()==0))<=funds+.001);
                    if(valid)low=mid;else high=mid-1;
                }
                amount=low;
                if(amount<=0){
                    if(o.buy&&withinLimit(o,executionPrice(s,true,1))&&funds<executionPrice(s,true,1)+fee(executionPrice(s,true,1),o.filled()==0)){
                        o.status="ABGEBROCHEN";o.reason="Kurslücke: Guthaben reicht nicht für eine weitere Aktie.";
                        notifyPlayer(o.symbol+": Restorder mangels Kaufkraft storniert.");
                    }
                    continue;
                }
                double p=executionPrice(s,o.buy,amount),gross=cents(amount*p),f=fee(gross,o.filled()==0),profit=0;
                Position pos=position(o.symbol);
                if(o.buy){
                    double cost=cents(gross+f);pos.average=(pos.average*pos.quantity+cost)/(pos.quantity+amount);
                    pos.quantity+=amount;cash=cents(cash-cost);cashflow("KAUF",amount+" × "+o.symbol,-cost);
                }else{
                    profit=cents(gross-f-pos.average*amount);pos.quantity-=amount;
                    if(pos.quantity==0)pos.average=0;
                    cash=cents(cash+gross-f);realized+=profit;cashflow("VERKAUF",amount+" × "+o.symbol,gross-f);
                }
                totalFees+=f;o.value+=gross;o.fees+=f;o.remaining-=amount;s.liquidity-=amount;
                o.status=o.remaining==0?"AUSGEFÜHRT":"TEILWEISE";
                fills.add(0,new Fill(tick(),o.symbol,o.buy,amount,p,f,profit,o.id));if(fills.size()>6000)fills.remove(fills.size()-1);
                // Small, decaying order-flow feedback. It does not guarantee a profitable round-trip.
                s.newsDrift+=clamp((o.buy?1:-1)*amount/Math.max(1.0,s.averageVolume)*.035,-.001,.001);
                notifyPlayer((o.buy?"Gekauft: ":"Verkauft: ")+amount+" × "+o.symbol+" zu "+eur(p)+(o.remaining>0?" · Teilausführung":""));
            }
        }

        void cancel(Order o){if(!o.active())throw new IllegalArgumentException("Diese Order ist nicht mehr offen.");o.status="STORNIERT";o.reason="Vom Spieler storniert";notifyPlayer("Order #"+o.id+" storniert. Reserven freigegeben.");}
        int maxBuy(String symbol){Stock s=stocks.get(symbol);double per=s.ask(this)*1.04*(1+feeRate());return Math.max(0,(int)Math.floor((freeCash()-.95)/per));}
        void addAlert(String sy,double target){
            if(!Double.isFinite(target)||target<.01||target>1e9)throw new IllegalArgumentException("Ungültiger Alarmkurs.");
            if(alerts.stream().filter(a->a.active).count()>=40)throw new IllegalArgumentException("Maximal 40 aktive Alarme.");
            PriceAlert a=new PriceAlert();a.symbol=sy;a.target=target;a.up=target>=stocks.get(sy).price;alerts.add(a);
        }
        void checkAlerts(Stock s,double old){for(PriceAlert a:alerts)if(a.active&&a.symbol.equals(s.symbol)&&(a.up?s.price>=a.target:s.price<=a.target)){
            a.active=false;notifyPlayer("Kursalarm: "+s.symbol+" hat "+eur(a.target)+" erreicht.");
            addNews("KURSALARM",s.symbol+" erreicht dein Kursziel.","Alarmkurs "+eur(a.target)+"; aktueller Kurs "+eur(s.price)+".",s.symbol,0);
        }}

        double researchCost(Stock s){return new double[]{180,650,1800,1200}[Math.min(3,s.research)];}
        int researchDays(Stock s){return new int[]{1,3,5,2}[Math.min(3,s.research)];}
        int researchSlots(){return 1+upgrade("lab");}
        boolean researching(String key){return projects.stream().anyMatch(p->p.key.equals(key));}
        void startResearch(String symbol){
            Stock s=stocks.get(symbol);if(s==null)throw new IllegalArgumentException("Unbekanntes Unternehmen.");
            double cost=researchCost(s);int level=Math.min(3,s.research+1);
            startProject("stock:"+symbol,s.research==3?symbol+" · Modell aktualisieren":symbol+" · Analyse Stufe "+level,cost,researchDays(s),level);
        }
        String upgradeName(String key){return switch(key){case "routing"->"Order-Routing";case "lab"->"Research-Lab";case "private"->"Private Markets";case "estate"->"Immobilienmanagement";default->throw new IllegalArgumentException("Unbekannte Forschung.");};}
        double upgradeCost(String key){return (key.equals("routing")?1500:key.equals("lab")?2200:key.equals("private")?2800:1800)*Math.pow(2.6,upgrade(key));}
        void startUpgrade(String key){if(upgrade(key)>=3)throw new IllegalArgumentException("Bereits maximal ausgebaut.");startProject(key,upgradeName(key)+" · Stufe "+(upgrade(key)+1),upgradeCost(key),2+upgrade(key)*2,upgrade(key)+1);}
        void startProject(String key,String title,double cost,int days,int level){
            if(researching(key))throw new IllegalArgumentException("Dieses Projekt läuft bereits.");
            if(projects.size()>=researchSlots())throw new IllegalArgumentException("Alle Forschungsplätze sind belegt.");
            if(freeCash()<cost)throw new IllegalArgumentException("Nicht genügend freies Guthaben.");
            cash=cents(cash-cost);cashflow("FORSCHUNG",title,-cost);
            int duration=(int)Math.ceil(days*SESSION*(1-.12*upgrade("lab")));
            projects.add(new Research(key,title,tick(),tick()+duration,level,cost));notifyPlayer("Forschung gestartet: "+title);
        }
        void finishResearch(){
            Iterator<Research> it=projects.iterator();
            while(it.hasNext()){
                Research p=it.next();if(tick()<p.end)continue;
                if(p.key.startsWith("stock:")){
                    Stock s=stocks.get(p.key.substring(6));s.research=p.level;
                    s.analystUncertainty=new double[]{.30,.25,.16,.10}[s.research];
                    s.analystValue=s.fairValue*Math.exp(rng.gaussian()*s.analystUncertainty*.55);
                    s.analystDay=day;
                }else upgrades.put(p.key,p.level);
                researchCompleted++;it.remove();notifyPlayer("Abgeschlossen: "+p.title);
                addNews("RESEARCH",p.title+" abgeschlossen.","Die neue Analyse bzw. das Upgrade ist verfügbar. Analysen sind Schätzungen und können falsch oder veraltet sein.",p.key.startsWith("stock:")?p.key.substring(6):"",0);
            }
        }

        Stake activeStake(String id){return stakes.stream().filter(s->s.ventureId.equals(id)&&s.active()).findFirst().orElse(null);}
        void invest(Venture v){
            if(upgrade("private")<v.requirement)throw new IllegalArgumentException("Private Markets Stufe "+v.requirement+" erforderlich.");
            if(activeStake(v.id)!=null)throw new IllegalArgumentException("Du bist bereits in dieser Runde investiert.");
            if(freeCash()<v.ticket)throw new IllegalArgumentException("Nicht genügend freies Guthaben.");
            Stake s=new Stake();s.ventureId=v.id;s.name=v.name;s.invested=v.ticket;s.mark=v.ticket;
            s.ownership=v.ticket/(v.valuation+v.ticket);s.startDay=day;s.exitDay=day+v.duration;s.failure=v.failure;
            cash=cents(cash-v.ticket);stakes.add(s);cashflow("PRIVATE EQUITY",v.name,-v.ticket);
            notifyPlayer("Beteiligung an "+v.name+" abgeschlossen.");checkAchievements();
        }
        void updatePrivate(){
            for(Stake s:stakes)if(s.active()){
                if(day>=s.exitDay){
                    double r=rng.next(),multiple;
                    if(r<s.failure)multiple=0;
                    else {double result=rng.next();multiple=result<.28?.55+rng.next()*.4:result<.80?1.15+rng.next()*1.35:3+rng.next()*4.5;}
                    // Successful rounds dilute the player's holding before exit.
                    double dilution=multiple>1?(.78+rng.next()*.17):1;
                    s.ownership*=dilution;s.payout=cents(s.invested*multiple*dilution);
                    s.status=s.payout==0?"AUSGEFALLEN":"EXIT";s.mark=0;
                    cash=cents(cash+s.payout);privateProfit+=s.payout-s.invested;privateExits++;
                    cashflow("PRIVATE EXIT",s.name,s.payout);notifyPlayer(s.name+": Exit mit "+eur(s.payout)+" Rückfluss.");
                    addNews("PRIVATE MARKETS",s.name+(s.payout==0?": Finanzierungsrunde gescheitert.":": Beteiligung ausgezahlt."),
                        "Investiert: "+eur(s.invested)+" · Rückfluss nach möglicher Verwässerung: "+eur(s.payout)+". Private Beteiligungen können vollständig ausfallen.","",s.payout>s.invested?1:-1);
                }else if(day%5==0){
                    s.mark=cents(clamp(s.mark*Math.exp(rng.gaussian()*.055+(regime==2?-.025:.005)),s.invested*.15,s.invested*3.5));
                }
            }
        }
        void sellStake(Stake s){
            if(!s.active())throw new IllegalArgumentException("Diese Beteiligung ist beendet.");
            if(upgrade("private")<2)throw new IllegalArgumentException("Sekundärmarkt ab Private Markets Stufe 2.");
            s.payout=cents(s.mark*.65*.99);s.mark=0;s.status="SEKUNDÄR-EXIT";
            cash=cents(cash+s.payout);privateProfit+=s.payout-s.invested;privateExits++;
            cashflow("SEKUNDÄR-EXIT",s.name,s.payout);notifyPlayer("Beteiligung verkauft: "+eur(s.payout)+" (35 % Abschlag + 1 % Gebühr).");
        }

        Property ownedProperty(String id){return properties.stream().filter(p->p.id.equals(id)).findFirst().orElse(null);}
        double offerPrice(PropertyOffer o){return cents(o.price*Math.exp(.02*day/252));}
        double propertyUpfront(PropertyOffer o,boolean mortgage){return cents(offerPrice(o)*(mortgage?.36:1.06));}
        void buyProperty(PropertyOffer o,boolean mortgage){
            if(ownedProperty(o.id)!=null)throw new IllegalArgumentException("Dieses Objekt gehört dir bereits.");
            double price=offerPrice(o),cost=propertyUpfront(o,mortgage);
            if(freeCash()<cost)throw new IllegalArgumentException("Benötigtes freies Eigenkapital inklusive 6 % Kaufkosten: "+eur(cost));
            Property p=new Property();p.id=o.id;p.name=o.name;p.value=price;p.basis=price*1.06;
            p.debt=mortgage?cents(price*.70):0;p.rate=rate+.022;
            double monthly=p.rate/12;p.payment=p.debt==0?0:cents(p.debt*monthly/(1-Math.pow(1+monthly,-240)));
            p.rentYield=o.yield;p.occupancy=o.occupancy;p.boughtDay=day;
            cash=cents(cash-cost);properties.add(p);cashflow("IMMOBILIENKAUF",o.name,-cost);checkAchievements();
            notifyPlayer("Willkommen im Portfolio: "+o.name);
        }
        void updateProperties(){
            for(Property p:properties){
                p.value=cents(Math.max(100,p.value*Math.exp((economy*.35+.013)/252+rng.gaussian()*.0018)));
                if((day-p.boughtDay+1)%21!=0)continue;
                boolean occupied=rng.next()<p.occupancy;
                double rent=occupied?cents(p.value*p.rentYield/12):0;
                double maintenance=cents(p.value*.01*(1-.15*upgrade("estate"))/12);
                double interestPart=cents(p.debt*p.rate/12);
                double due=cents(maintenance+(p.debt>0?Math.min(p.payment,p.debt+interestPart):0));
                cash=cents(cash+rent);p.income+=rent-maintenance;propertyProfit+=rent-maintenance-interestPart;
                if(freeCash()<due){
                    // Mandatory bills take precedence over pending BUY orders.
                    for(Order order:orders)if(order.active()&&order.buy){order.status="STORNIERT";order.reason="Liquidität für Immobilienkosten";}
                }
                double paid=Math.min(cash,due);cash=cents(cash-paid);
                double mortgagePaid=Math.max(0,paid-maintenance);
                if(p.debt>0)p.debt=Math.max(0,cents(p.debt+interestPart-mortgagePaid));
                if(paid<due){
                    // Unpaid operating expenses become debt; no invisible negative cash.
                    p.debt=cents(p.debt+Math.max(0,maintenance-paid)+50);
                    p.payment=Math.max(p.payment,cents(p.debt*.008));
                    notifyPlayer(p.name+": Liquiditätsengpass. Rückstand + 50 € Gebühr wurden zur Schuld addiert.");
                }
                cashflow("MIETE / KOSTEN",p.name+(occupied?"":" · Leerstand"),rent-paid);
                if(!occupied)notifyPlayer(p.name+": diesen Monat Leerstand.");
            }
        }
        void renovate(Property p){
            if(p.level>=3)throw new IllegalArgumentException("Bereits vollständig modernisiert.");
            double cost=cents(p.value*.05);
            if(freeCash()<cost)throw new IllegalArgumentException("Modernisierung kostet "+eur(cost)+".");
            cash=cents(cash-cost);p.basis+=cost;p.value=cents(p.value*1.035);p.rentYield+=.004;p.occupancy=Math.min(.99,p.occupancy+.01);p.level++;
            cashflow("MODERNISIERUNG",p.name,-cost);notifyPlayer(p.name+": Modernisierung Stufe "+p.level+" abgeschlossen.");
        }
        void sellProperty(Property p){
            double net=cents(p.value*.97-p.debt);
            if(freeCash()+net<0)throw new IllegalArgumentException("Die Restschuld übersteigt Verkaufserlös plus freies Guthaben.");
            cash=cents(cash+net);propertyProfit+=p.value*.97-p.basis;properties.remove(p);
            cashflow("IMMOBILIENVERKAUF",p.name,net);notifyPlayer(p.name+" verkauft. Netto nach Kosten und Kredit: "+eur(net));
        }

        static final String[][] GOALS={
            {"first","Erster Schritt","Führe deinen ersten Aktienkauf aus.","100"},
            {"limit","Mit Präzision","Lass eine Limitorder vollständig ausführen.","150"},
            {"profit","Grüne Zahlen","Verkaufe eine Position mit realisiertem Gewinn.","200"},
            {"diverse","Breit aufgestellt","Halte Aktien aus vier verschiedenen Sektoren.","250"},
            {"research","Wissen ist Kapital","Schließe deine erste Forschung ab.","200"},
            {"private","Hinter den Kulissen","Investiere in eine private Finanzierungsrunde.","250"},
            {"estate","Grundbesitzer","Kaufe deine erste Immobilie.","250"},
            {"income","Cashflow","Erhalte eine Dividende auf deine Aktien.","150"},
            {"veteran","Markterfahrung","Erlebe 21 vollständige Handelstage.","300"},
            {"growth","Kapitalwachstum","Erhöhe dein Startvermögen um 25 %.","400"},
            {"double","Verdoppelt","Verdopple dein Startvermögen.","600"},
            {"million","Der große Meilenstein","Erreiche mindestens 1 Mio. € und 25 % Wachstum.","1000"}
        };
        void checkAchievements(){
            Set<Integer> sectors=new HashSet<>();for(Map.Entry<String,Position> e:positions.entrySet())if(e.getValue().quantity>0)sectors.add(stocks.get(e.getKey()).sector);
            boolean[] ok={!fills.isEmpty(),orders.stream().anyMatch(o->o.type.equals("LIMIT")&&o.status.equals("AUSGEFÜHRT")),
                fills.stream().anyMatch(f->!f.buy&&f.realized>0),sectors.size()>=4,researchCompleted>0,!stakes.isEmpty(),!properties.isEmpty(),dividends>0,
                day>=21,wealth()>=initialCapital*1.25,wealth()>=initialCapital*2,wealth()>=Math.max(1e6,initialCapital*1.25)};
            for(int i=0;i<ok.length;i++)if(ok[i]&&achievements.add(GOALS[i][0]))notifyPlayer("Meilenstein: "+GOALS[i][1]+" · +"+GOALS[i][3]+" XP");
        }
        int xp(){int x=0;for(String[] g:GOALS)if(achievements.contains(g[0]))x+=Integer.parseInt(g[3]);return x;}
        String rank(){int x=xp();return x>=2300?"Capital Architect":x>=1200?"Portfolio Manager":x>=500?"Analyst":"Newcomer";}
    }

    // ───────────────────── 4. PERSISTENCE / EXPORT ──────────────────────────

    static class Storage {
        static final int MAGIC=0x4155524c,FORMAT=1;
        static byte[] encode(Game game)throws IOException{
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(ObjectOutputStream out=new ObjectOutputStream(new GZIPOutputStream(bytes))){out.writeObject(game);}
            byte[] payload=bytes.toByteArray();CRC32 crc=new CRC32();crc.update(payload);
            ByteArrayOutputStream file=new ByteArrayOutputStream();
            try(DataOutputStream out=new DataOutputStream(file)){out.writeInt(MAGIC);out.writeInt(FORMAT);out.writeInt(payload.length);out.writeLong(crc.getValue());out.write(payload);}
            return file.toByteArray();
        }
        static void write(Path path,byte[] bytes)throws IOException{
            Path parent=path.toAbsolutePath().getParent();Files.createDirectories(parent);
            Path tmp=Files.createTempFile(parent,"aurel-",".tmp");
            try{
                Files.write(tmp,bytes);
                if(Files.exists(path))Files.copy(path,path.resolveSibling(path.getFileName()+".bak"),StandardCopyOption.REPLACE_EXISTING);
                try{Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(AtomicMoveNotSupportedException e){Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);}
            }finally{Files.deleteIfExists(tmp);}
        }
        static Game load(Path path)throws IOException,ClassNotFoundException{
            if(Files.size(path)>32*1024*1024)throw new IOException("Datei zu groß.");
            byte[] payload;
            try(DataInputStream in=new DataInputStream(Files.newInputStream(path))){
                if(in.readInt()!=MAGIC||in.readInt()!=FORMAT)throw new IOException("Kein kompatibler AUREL-Spielstand.");
                int size=in.readInt();long expected=in.readLong();
                if(size<1||size>32*1024*1024)throw new IOException("Ungültige Länge.");
                payload=in.readNBytes(size);if(payload.length!=size||in.read()!=-1)throw new IOException("Unvollständige oder unerwartete Daten.");
                CRC32 crc=new CRC32();crc.update(payload);if(crc.getValue()!=expected)throw new IOException("Prüfsumme stimmt nicht.");
            }
            final Set<String> allowed=Set.of("java.util.ArrayList","java.util.LinkedHashMap","java.util.HashMap","java.util.HashSet",
                "java.lang.Integer","java.lang.Number","java.lang.String","java.lang.Object","java.util.Map$Entry");
            try(ObjectInputStream in=new ObjectInputStream(new GZIPInputStream(new ByteArrayInputStream(payload)))){
                in.setObjectInputFilter(info->{
                    if(info.depth()>35||info.references()>800000||info.streamBytes()>96L*1024*1024||info.arrayLength()>800000)return ObjectInputFilter.Status.REJECTED;
                    Class<?> c=info.serialClass();if(c==null)return ObjectInputFilter.Status.UNDECIDED;
                    while(c.isArray())c=c.getComponentType();
                    if(c.isPrimitive()||allowed.contains(c.getName())||isModelClass(c))return ObjectInputFilter.Status.ALLOWED;
                    return ObjectInputFilter.Status.REJECTED;
                });
                Object object=in.readObject();if(!(object instanceof Game))throw new IOException("Falscher Inhalt.");
                Game g=(Game)object;validate(g);g.notifications=new ArrayList<>();return g;
            }
        }
        static boolean isModelClass(Class<?> c){return c==Game.class||c==Rng.class||c==Candle.class||c==Stock.class||c==Position.class||c==Order.class||c==Fill.class||c==News.class||c==Research.class||c==Venture.class||c==Stake.class||c==PropertyOffer.class||c==Property.class||c==PriceAlert.class||c==EquityPoint.class||c==Ledger.class;}
        static void validate(Game g)throws IOException{
            if(g.stocks==null||g.stocks.size()!=24||g.rng==null||!Double.isFinite(g.cash)||g.cash<0||g.slot<0||g.slot>SESSION||g.day<0||g.day>1000000)throw new IOException("Unplausibler Spielstand.");
            if(g.positions==null||g.orders==null||g.equity==null||g.projects==null||g.properties==null||g.stakes==null||g.watchlist==null)throw new IOException("Unvollständiger Spielstand.");
            for(Stock s:g.stocks.values())if(s==null||!Double.isFinite(s.price)||s.price<.01||s.intraday.isEmpty()||s.daily.isEmpty())throw new IOException("Ungültige Kurshistorie.");
            for(Map.Entry<String,Position> e:g.positions.entrySet())if(!g.stocks.containsKey(e.getKey())||e.getValue().quantity<0||!Double.isFinite(e.getValue().average))throw new IOException("Ungültige Position.");
            for(Order o:g.orders)if(!g.stocks.containsKey(o.symbol)||o.remaining<0||o.remaining>o.quantity)throw new IOException("Ungültige Order.");
        }
        static void export(Game g,Path p)throws IOException{
            StringBuilder csv=new StringBuilder("\uFEFFDatum;Uhrzeit;Order;Aktie;Seite;Stueck;Kurs_EUR;Gebuehr_EUR;Realisiert_EUR\r\n");
            for(int i=g.fills.size()-1;i>=0;i--){Fill f=g.fills.get(i);csv.append(date(eventDay(f.tick))).append(';').append(time(eventSlot(f.tick))).append(';').append(f.orderId).append(';').append(f.symbol).append(';').append(f.buy?"KAUF":"VERKAUF").append(';').append(f.quantity).append(';').append(String.format(Locale.GERMANY,"%.2f;%.2f;%.2f\r\n",f.price,f.fee,f.realized));}
            Files.writeString(p,csv,StandardCharsets.UTF_8);
        }
    }

    // ───────────────────── 5. DESKTOP WINDOW / DIALOGS ──────────────────────

    static class Theme {
        Color background,sidebar,card,elevated,border,text,muted,faint,green,red,accent,gold;
        boolean light;
        Theme(boolean light){
            this.light=light;
            background=new Color(light?0xF3F5F7:0x0B0E12);
            sidebar=new Color(light?0xFAFBFC:0x101318);
            card=new Color(light?0xFFFFFF:0x14181E);
            elevated=new Color(light?0xF0F2F5:0x1D222A);
            border=new Color(light?0xE1E5EB:0x282E37);
            text=new Color(light?0x18202B:0xF0F3F7);
            muted=new Color(light?0x647183:0x9AA4B3);
            faint=new Color(light?0x8D99A7:0x626E7E);
            green=new Color(light?0x147A53:0xA4E8BC);
            red=new Color(light?0xCB4554:0xEC8997);
            accent=new Color(light?0x7560C9:0xB9ADF4);
            gold=new Color(light?0xAC7B29:0xDBC28E);
        }
    }
    static Color alpha(Color c,int a){return new Color(c.getRed(),c.getGreen(),c.getBlue(),Math.max(0,Math.min(255,a)));}
    static Color blend(Color a,Color b,double t){return new Color((int)(a.getRed()*(1-t)+b.getRed()*t),(int)(a.getGreen()*(1-t)+b.getGreen()*t),(int)(a.getBlue()*(1-t)+b.getBlue()*t));}

    static class Window extends JFrame {
        private static final long serialVersionUID=1L;
        Game game;
        Board board;
        boolean paused=true,captureMode,closing,busy;
        int speed=1;
        long lastFrame=System.nanoTime(),lastSave=System.currentTimeMillis();
        double accumulated;
        String savedStatus="Noch nicht gespeichert";
        javax.swing.Timer timer,fastForward;
        final transient ExecutorService disk=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"aurel-save");t.setDaemon(false);return t;});

        Window(Game g,boolean capture){
            super("AUREL  ·  Capital Simulator");game=g;captureMode=capture;
            setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            setMinimumSize(new Dimension(1080,700));
            setSize(1510,960);setLocationRelativeTo(null);
            setIconImage(appIcon());
            board=new Board(this);setContentPane(board);
            addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent e){closeGame();}});
            timer=new javax.swing.Timer(33,e->frame());timer.start();
            bind("SPACE",()->{if(!busy){paused=!paused;accumulated=0;}});
            bind("control S",()->save(false));bind("meta S",()->save(false));
            bind("control F",()->board.search());bind("meta F",()->board.search());
            bind("ESCAPE",()->{board.searchQuery="";board.pageScroll=0;board.marketScroll=0;});
            for(int i=1;i<=9;i++){final int k=i-1;bind(Integer.toString(i),()->board.navigate(Board.PAGES[k]));}
        }
        void bind(String key,Runnable action){
            String id="aurel:"+key;
            board.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key),id);
            board.getActionMap().put(id,new AbstractAction(){@Override public void actionPerformed(ActionEvent e){action.run();}});
        }
        BufferedImage appIcon(){
            BufferedImage i=new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB);
            Graphics2D g=i.createGraphics();Board.quality(g);g.setColor(new Color(0x11171B));g.fillRoundRect(0,0,128,128,30,30);
            g.setColor(new Color(0xB2F0CA));g.setStroke(new BasicStroke(9,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            Path2D p=new Path2D.Double();p.moveTo(32,92);p.lineTo(64,32);p.lineTo(96,92);g.draw(p);g.drawLine(48,72,80,72);g.dispose();return i;
        }
        void frame(){
            long now=System.nanoTime();double elapsed=Math.min(.25,(now-lastFrame)/1e9);lastFrame=now;
            if(!paused&&!busy){
                accumulated+=elapsed*speed;
                int count=0;
                while(accumulated>=1&&count<32){game.step();accumulated--;count++;}
            }
            if(game.notifications!=null&&!game.notifications.isEmpty()){
                int n=game.notifications.size();showToast(game.notifications.get(n-1)+(n>1?"  (+"+(n-1)+" weitere Ereignisse)":""));game.notifications.clear();
            }
            if(!captureMode&&!closing&&System.currentTimeMillis()-lastSave>30000)save(true);
            board.repaint();
        }
        void showToast(String text){board.toast=text;board.toastAt=System.currentTimeMillis();board.repaint();}
        void runAction(Runnable r){try{r.run();board.repaint();}catch(IllegalArgumentException e){error(e.getMessage());}catch(Exception e){e.printStackTrace();error("Die Aktion konnte nicht abgeschlossen werden: "+e.getMessage());}}
        void error(String message){dialog("Nicht möglich",message,new String[0],new String[0],"Verstanden",false);}
        void confirm(String title,String body,Runnable action){if(dialog(title,body,new String[0],new String[0],"Bestätigen",true)!=null)runAction(action);}

        /** A real Swing form supplies keyboard navigation, copy/paste and input methods. */
        String[] dialog(String title,String body,String[] labels,String[] values,String ok,boolean cancel){
            boolean wasPaused=paused;paused=true;
            Theme t=new Theme(game.light);
            JDialog d=new JDialog(this,title,true);d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            JPanel root=new JPanel(new BorderLayout(0,20));root.setBackground(t.card);root.setBorder(new EmptyBorder(27,28,24,28));
            JPanel content=new JPanel();content.setOpaque(false);content.setLayout(new BoxLayout(content,BoxLayout.Y_AXIS));
            JLabel heading=new JLabel(title);heading.setForeground(t.text);heading.setFont(font(24,true));heading.setAlignmentX(Component.LEFT_ALIGNMENT);content.add(heading);content.add(Box.createVerticalStrut(13));
            JTextArea text=new JTextArea(body);text.setEditable(false);text.setFocusable(false);text.setLineWrap(true);text.setWrapStyleWord(true);text.setFont(font(14,false));text.setForeground(t.muted);text.setBackground(t.card);
            text.setBorder(null);text.setAlignmentX(Component.LEFT_ALIGNMENT);text.setColumns(47);
            text.setSize(500,Short.MAX_VALUE);Dimension pref=text.getPreferredSize();text.setMaximumSize(new Dimension(Integer.MAX_VALUE,Math.max(42,pref.height+5)));
            content.add(text);
            JTextField[] fields=new JTextField[labels.length];
            for(int i=0;i<labels.length;i++){
                content.add(Box.createVerticalStrut(17));JLabel l=new JLabel(labels[i]);l.setFont(font(12,true));l.setForeground(t.muted);l.setAlignmentX(Component.LEFT_ALIGNMENT);content.add(l);content.add(Box.createVerticalStrut(7));
                JTextField f=new JTextField(values[i]);f.setFont(font(17,false));f.setBackground(t.elevated);f.setForeground(t.text);f.setCaretColor(t.accent);
                f.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(t.border),new EmptyBorder(10,12,10,12)));
                f.setMaximumSize(new Dimension(Integer.MAX_VALUE,44));f.setAlignmentX(Component.LEFT_ALIGNMENT);fields[i]=f;content.add(f);
            }
            root.add(content,BorderLayout.CENTER);
            JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,10,0));actions.setOpaque(false);
            final String[][] answer={null};
            if(cancel){JButton b=dialogButton("Abbrechen",t.elevated,t.text);b.addActionListener(e->d.dispose());actions.add(b);}
            JButton go=dialogButton(ok,t.green,new Color(game.light?0xFFFFFF:0x14211A));go.addActionListener(e->{
                answer[0]=new String[fields.length];for(int i=0;i<fields.length;i++)answer[0][i]=fields[i].getText().trim();d.dispose();
            });actions.add(go);root.add(actions,BorderLayout.SOUTH);d.setContentPane(root);d.getRootPane().setDefaultButton(go);
            d.getRootPane().registerKeyboardAction(e->d.dispose(),KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),JComponent.WHEN_IN_FOCUSED_WINDOW);
            d.setSize(590,Math.min(760,Math.max(245,150+Math.max(45,pref.height)+labels.length*83)));d.setResizable(false);d.setLocationRelativeTo(this);
            if(fields.length>0)SwingUtilities.invokeLater(()->{fields[0].requestFocusInWindow();fields[0].selectAll();});
            d.setVisible(true);paused=wasPaused;lastFrame=System.nanoTime();return answer[0];
        }
        JButton dialogButton(String label,Color bg,Color fg){
            JButton b=new JButton(label);b.setFont(font(14,true));b.setFocusPainted(false);b.setBackground(bg);b.setForeground(fg);b.setOpaque(true);
            b.setBorder(new EmptyBorder(11,18,11,18));b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));return b;
        }
        Double inputNumber(String title,String body,String label,double initial){
            String[] values=dialog(title,body,new String[]{label},new String[]{String.format(Locale.GERMANY,"%.2f",initial)},"Übernehmen",true);
            if(values==null)return null;
            try{return parseNumber(values[0]);}catch(IllegalArgumentException ex){error(ex.getMessage());return null;}
        }
        static double parseNumber(String raw){
            String s=raw.replace("€","").replace("%","").replace(" ","").replace("\u00a0","");
            if(s.contains(","))s=s.replace(".","").replace(',','.');
            try{double v=Double.parseDouble(s);if(!Double.isFinite(v)||Math.abs(v)>1e12)throw new NumberFormatException();return v;}
            catch(NumberFormatException e){throw new IllegalArgumentException("Bitte eine gültige Zahl eingeben, zum Beispiel 125,50.");}
        }
        void choose(String title,List<String> labels,IntConsumer action){
            Theme t=new Theme(game.light);JPopupMenu popup=new JPopupMenu();popup.setBackground(t.card);popup.setBorder(BorderFactory.createLineBorder(t.border));
            for(int i=0;i<labels.size();i++){
                final int index=i;JMenuItem item=new JMenuItem(labels.get(i));item.setFont(font(14,false));item.setBorder(new EmptyBorder(10,15,10,15));item.setBackground(t.card);item.setForeground(t.text);
                item.addActionListener(e->runAction(()->action.accept(index)));popup.add(item);
            }
            Point p=board.getMousePosition();popup.show(board,p==null?500:p.x,p==null?300:p.y);
        }
        void save(boolean auto){
            if(captureMode)return;
            lastSave=System.currentTimeMillis();
            try{
                byte[] snapshot=Storage.encode(game);savedStatus="Wird gespeichert …";
                disk.submit(()->{try{Storage.write(SAVE,snapshot);SwingUtilities.invokeLater(()->{savedStatus="Lokal gespeichert · "+LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));if(!auto)showToast("Spielstand gespeichert. Eine Sicherung der vorherigen Version liegt daneben.");});}
                    catch(Exception e){SwingUtilities.invokeLater(()->{savedStatus="Speichern fehlgeschlagen";if(!auto)error(e.getMessage());else showToast("Speichern fehlgeschlagen: "+e.getMessage());});}});
            }catch(Exception e){savedStatus="Speichern fehlgeschlagen";showToast(e.getMessage());}
        }
        void closeGame(){
            if(closing)return;closing=true;paused=true;timer.stop();if(fastForward!=null)fastForward.stop();busy=false;
            if(captureMode){disk.shutdown();dispose();return;}
            try{
                byte[] snapshot=Storage.encode(game);setTitle("AUREL · Spielstand wird gesichert …");
                disk.submit(()->{try{Storage.write(SAVE,snapshot);SwingUtilities.invokeLater(()->{disk.shutdown();dispose();});}
                    catch(Exception ex){SwingUtilities.invokeLater(()->{closing=false;timer.start();error("Speichern vor dem Schließen fehlgeschlagen: "+ex.getMessage()+". Das Fenster bleibt offen.");});}});
            }catch(Exception e){closing=false;timer.start();error(e.getMessage());}
        }
        void loadGame(){
            Game previousGame=game;boolean was=paused;paused=true;JFileChooser chooser=new JFileChooser(HOME.toFile());chooser.setDialogTitle("AUREL-Spielstand laden");
            if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION){Path path=chooser.getSelectedFile().toPath();
                confirm("Spielstand laden?","Der aktuelle Fortschritt wird ersetzt. Nutze vorher Speichern, um ihn zu behalten.",()->{
                    try{game=Storage.load(path);board.resetView();paused=true;accumulated=0;showToast("Spielstand geladen.");save(true);}
                    catch(Exception e){error("Spielstand konnte nicht geladen werden: "+e.getMessage());}
                });
            }paused=game==previousGame?was:true;lastFrame=System.nanoTime();
        }
        void exportTrades(){
            boolean was=paused;paused=true;JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new File("AUREL-Trades.csv"));
            if(chooser.showSaveDialog(this)==JFileChooser.APPROVE_OPTION){Path p=chooser.getSelectedFile().toPath();Runnable write=()->{
                try{Storage.export(game,p);showToast("Trading-Journal als CSV exportiert.");}catch(IOException e){error(e.getMessage());}
            };if(Files.exists(p))confirm("Datei überschreiben?",p.toString(),write);else write.run();}
            paused=was;lastFrame=System.nanoTime();
        }
        void newGame(){
            String[] v=dialog("Eine neue Ära","Dies ersetzt die aktuelle Karriere. Startkapital und Seed sind frei wählbar. Derselbe Seed erzeugt bei denselben Aktionen denselben Markt.\n\nChallenge: 25.000 € · Standard: 100.000 € · Sandbox: 1.000.000 €.",
                new String[]{"Startkapital in Euro","Welt-Seed (ganze Zahl)"},new String[]{"100000",Long.toString(System.currentTimeMillis()%1000000000)},"Neue Karriere starten",true);
            if(v==null)return;
            runAction(()->{
                double capital=parseNumber(v[0]);if(capital<1000||capital>1e9)throw new IllegalArgumentException("Startkapital: 1.000 bis 1 Milliarde Euro.");
                long seed;try{seed=Long.parseLong(v[1]);}catch(NumberFormatException e){throw new IllegalArgumentException("Der Seed muss eine ganze Zahl sein.");}
                game=Game.create(seed,cents(capital));board.resetView();paused=true;accumulated=0;save(true);showToast("Deine neue Karriere ist bereit.");
            });
        }
        void advanceDays(int days){
            if(busy)return;paused=true;busy=true;
            final int[] remaining={(game.slot>=SESSION?SESSION:SESSION-game.slot)+(days-1)*SESSION};
            final int total=remaining[0];javax.swing.Timer batch=new javax.swing.Timer(12,null);fastForward=batch;
            batch.addActionListener(e->{
                int n=Math.min(60,remaining[0]);game.advance(n);remaining[0]-=n;
                board.toast="Simuliere "+days+" Handelstag"+(days>1?"e":"")+" … "+(100*(total-remaining[0])/total)+" %";board.toastAt=System.currentTimeMillis();board.repaint();
                if(remaining[0]<=0){batch.stop();busy=false;accumulated=0;showToast("Simulation abgeschlossen. Markt pausiert.");}
            });batch.start();
        }
        void help(){
            boolean was=paused;paused=true;Theme t=new Theme(game.light);
            JDialog d=new JDialog(this,"AUREL · Spielanleitung",true);d.setSize(800,735);d.setLocationRelativeTo(this);
            JEditorPane pane=new JEditorPane("text/html",Board.helpHtml(game.light));pane.setEditable(false);pane.setBackground(t.card);pane.setCaretPosition(0);
            JScrollPane scroll=new JScrollPane(pane);scroll.setBorder(null);scroll.getVerticalScrollBar().setUnitIncrement(18);d.add(scroll);d.setVisible(true);paused=was;lastFrame=System.nanoTime();
        }
        void captureScreens(){
            timer.stop();paused=true;
            javax.swing.Timer capture=new javax.swing.Timer(650,null);
            final int[] step={0};String[] pages={"markets","dashboard","research","private","estate","portfolio","news","goals","settings"};
            capture.addActionListener(e->{
                try{
                    if(step[0]>=pages.length){capture.stop();disk.shutdown();dispose();return;}
                    board.navigate(pages[step[0]]);board.toast="";board.paintImmediately(0,0,board.getWidth(),board.getHeight());
                    BufferedImage image=new BufferedImage(board.getWidth(),board.getHeight(),BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics=image.createGraphics();board.paint(graphics);graphics.dispose();
                    Path directory=Paths.get(System.getProperty("aurel.capture","screenshots"));Files.createDirectories(directory);
                    ImageIO.write(image,"png",directory.resolve(String.format(Locale.ROOT,"%02d-%s.png",step[0]+1,pages[step[0]])).toFile());step[0]++;
                }catch(Exception ex){ex.printStackTrace();capture.stop();disk.shutdown();dispose();}
            });capture.start();
        }
    }

    static class Hit {
        Rectangle2D.Double rect;
        Runnable action;
        String tooltip;
        Hit(double x,double y,double w,double h,Runnable a,String tip){rect=new Rectangle2D.Double(x,y,w,h);action=a;tooltip=tip;}
    }

    static class Board extends JPanel {
        private static final long serialVersionUID=1L;
        static final String[] PAGES={"dashboard","markets","portfolio","research","private","estate","news","goals","settings"};
        static final String[] NAMES={"Übersicht","Märkte","Portfolio","Research","Private Equity","Immobilien","Nachrichten","Meilensteine","Einstellungen"};
        Window app;
        transient Theme t;
        transient Graphics2D g;
        String page="markets",selected="NOVA",range="1W",orderType="MARKET",tif="DAY",portfolioTab="Positionen",newsFilter="Alle",searchQuery="",sectorFilter="Alle Sektoren",sort="Standard";
        boolean buy=true,candles=true,bollinger=false,volume=true,logarithmic=false,onlyWatch=false,openOnly=true;
        int ma=1,quantity=10,visibleCandles=74,chartPan=0,marketScroll=0;
        double limitPrice=0,stopPrice=0,trail=.04;
        double mouseX=-1,mouseY=-1,W=1480,H=900,scale=1,pageScroll=0,contentHeight=0,hitDy=0;
        boolean dragging;double dragStartX;int dragStartPan;double totalDrag;
        transient Rectangle2D chartBounds=new Rectangle2D.Double();
        transient Rectangle2D watchBounds=new Rectangle2D.Double();
        transient List<Hit> hits=new ArrayList<>(),buildingHits;
        String toast="";long toastAt,hoverSince;String lastTip="";
        double cx=232,cw;

        Board(Window app){
            this.app=app;setOpaque(true);setBackground(new Color(0x0B0E12));setFocusable(true);
            MouseAdapter mouse=new MouseAdapter(){
                @Override public void mouseMoved(MouseEvent e){move(e);}
                @Override public void mouseExited(MouseEvent e){mouseX=-1;mouseY=-1;setCursor(Cursor.getDefaultCursor());repaint();}
                @Override public void mousePressed(MouseEvent e){move(e);if(page.equals("markets")&&chartBounds.contains(mouseX,mouseY)&&SwingUtilities.isLeftMouseButton(e)){dragging=true;dragStartX=mouseX;dragStartPan=chartPan;totalDrag=0;}}
                @Override public void mouseDragged(MouseEvent e){move(e);if(dragging){totalDrag=Math.abs(mouseX-dragStartX);chartPan=Math.max(0,dragStartPan+(int)((mouseX-dragStartX)/Math.max(2,chartBounds.getWidth()/visibleCandles)));repaint();}}
                @Override public void mouseReleased(MouseEvent e){dragging=false;}
                @Override public void mouseClicked(MouseEvent e){
                    move(e);if(totalDrag>4){totalDrag=0;return;}
                    if(e.getClickCount()==2&&chartBounds.contains(mouseX,mouseY)){chartPan=0;visibleCandles=74;repaint();return;}
                    for(int i=hits.size()-1;i>=0;i--){Hit h=hits.get(i);if(h.rect.contains(mouseX,mouseY)){app.runAction(h.action);repaint();return;}}
                }
                @Override public void mouseWheelMoved(MouseWheelEvent e){
                    move(e);
                    if(page.equals("markets")&&chartBounds.contains(mouseX,mouseY))visibleCandles=(int)clamp(visibleCandles+e.getWheelRotation()*6,24,190);
                    else if(page.equals("markets")&&watchBounds.contains(mouseX,mouseY))marketScroll=Math.max(0,marketScroll+e.getWheelRotation()*2);
                    else pageScroll=clamp(pageScroll+e.getPreciseWheelRotation()*52,0,Math.max(0,contentHeight-(H-138)));
                    repaint();
                }
            };addMouseListener(mouse);addMouseMotionListener(mouse);addMouseWheelListener(mouse);
        }
        Game game(){return app.game;}
        Stock stock(){return game().stocks.getOrDefault(selected,game().stocks.values().iterator().next());}
        void move(MouseEvent e){
            mouseX=e.getX()/scale;mouseY=e.getY()/scale;String tip="";boolean hand=false;
            for(int i=hits.size()-1;i>=0;i--)if(hits.get(i).rect.contains(mouseX,mouseY)){tip=hits.get(i).tooltip;hand=true;break;}
            if(!tip.equals(lastTip)){hoverSince=System.currentTimeMillis();lastTip=tip;}
            setCursor(Cursor.getPredefinedCursor(hand?Cursor.HAND_CURSOR:chartBounds.contains(mouseX,mouseY)&&page.equals("markets")?Cursor.CROSSHAIR_CURSOR:Cursor.DEFAULT_CURSOR));repaint();
        }
        void resetView(){selected="NOVA";page="markets";pageScroll=0;marketScroll=0;chartPan=0;limitPrice=0;stopPrice=0;quantity=10;searchQuery="";onlyWatch=false;}
        void navigate(String p){page=p;pageScroll=0;contentHeight=0;chartBounds=new Rectangle2D.Double();watchBounds=new Rectangle2D.Double();repaint();}
        void select(Stock s){selected=s.symbol;limitPrice=0;stopPrice=0;chartPan=0;}
        void search(){
            String[] v=app.dialog("Wertpapier suchen","Suche nach Unternehmensname oder Kürzel. Eine leere Suche zeigt wieder alle Werte.",new String[]{"Suchbegriff"},new String[]{searchQuery},"Suchen",true);
            if(v!=null){searchQuery=v[0];marketScroll=0;navigate("markets");}
        }
        static void quality(Graphics2D g){
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
        }
        @Override protected void paintComponent(Graphics graphics){
            super.paintComponent(graphics);g=(Graphics2D)graphics.create();quality(g);
            scale=Math.min(getWidth()/1480.0,getHeight()/880.0);W=getWidth()/scale;H=getHeight()/scale;cw=W-cx-28;g.scale(scale,scale);
            t=new Theme(game().light);buildingHits=new ArrayList<>();hitDy=0;
            fill(0,0,W,H,t.background);sidebar();header();
            Shape before=g.getClip();g.clip(new Rectangle2D.Double(218,86,W-218,H-123));
            g.translate(0,-pageScroll);hitDy=-pageScroll;
            switch(page){
                case "dashboard"->dashboard();case "markets"->markets();case "portfolio"->portfolio();case "research"->research();
                case "private"->privateMarkets();case "estate"->estate();case "news"->news();case "goals"->goals();default->settings();
            }
            g.translate(0,pageScroll);hitDy=0;g.setClip(before);footer();drawToast();drawTooltip();
            if(contentHeight>H-138&&!page.equals("markets")){
                double area=H-138,thumb=Math.max(45,area*area/contentHeight),offset=(area-thumb)*pageScroll/Math.max(1,contentHeight-area);
                rounded(W-7,100+offset,3,thumb,3,alpha(t.muted,75));
            }
            hits=buildingHits;g.dispose();
        }
        void hit(double x,double y,double w,double h,Runnable action,String tooltip){
            double yy=y+hitDy;
            // Buttons in scrolled cards must never catch clicks over the fixed toolbar.
            if(hitDy!=0&&(yy+h<88||yy>H-36))return;
            double y0=hitDy==0?yy:Math.max(88,yy),y1=hitDy==0?yy+h:Math.min(H-36,yy+h);
            if(y1>y0)buildingHits.add(new Hit(x,y0,w,y1-y0,action,tooltip==null?"":tooltip));
        }
        boolean hover(double x,double y,double w,double h){return mouseX>=x&&mouseX<=x+w&&mouseY>=y+hitDy&&mouseY<=y+hitDy+h;}
        void fill(double x,double y,double w,double h,Color c){g.setColor(c);g.fill(new Rectangle2D.Double(x,y,w,h));}
        void rounded(double x,double y,double w,double h,double r,Color c){g.setColor(c);g.fill(new RoundRectangle2D.Double(x,y,w,h,r,r));}
        void line(double x1,double y1,double x2,double y2,Color c){g.setColor(c);g.setStroke(new BasicStroke(1));g.draw(new Line2D.Double(x1,y1,x2,y2));}
        void text(String str,double x,double y,float size,Color c,boolean bold){g.setFont(displayFont(str,size,bold));g.setColor(c);g.drawString(str,(float)x,(float)y);}
        double tw(String str,float size,boolean bold){return g.getFontMetrics(displayFont(str,size,bold)).stringWidth(str);}
        void right(String str,double x,double y,float size,Color c,boolean bold){text(str,x-tw(str,size,bold),y,size,c,bold);}
        void center(String str,double x,double y,float size,Color c,boolean bold){text(str,x-tw(str,size,bold)/2,y,size,c,bold);}
        String cut(String s,double width,float size,boolean bold){if(tw(s,size,bold)<=width)return s;while(s.length()>1&&tw(s+"…",size,bold)>width)s=s.substring(0,s.length()-1);return s+"…";}
        int wrap(String str,double x,double y,double width,float size,Color c,int maxLines){
            String[] words=str.split("\\s+");String row="";int n=0;
            for(int i=0;i<words.length;i++){
                String candidate=row.isEmpty()?words[i]:row+" "+words[i];
                if(tw(candidate,size,false)>width&&!row.isEmpty()){
                    if(n==maxLines-1){text(cut(row+" "+words[i]+" …",width,size,false),x,y+n*(size+6),size,c,false);return n+1;}
                    text(row,x,y+n*(size+6),size,c,false);n++;row=words[i];
                }else row=candidate;
            }
            if(n<maxLines){text(row,x,y+n*(size+6),size,c,false);n++;}return n;
        }
        void card(double x,double y,double w,double h){
            if(!t.light)rounded(x,y+5,w,h,22,new Color(0,0,0,30));
            rounded(x,y,w,h,22,t.card);g.setColor(t.border);g.setStroke(new BasicStroke(1));g.draw(new RoundRectangle2D.Double(x+.5,y+.5,w-1,h-1,22,22));
        }
        void button(String label,double x,double y,double w,double h,boolean primary,Runnable action,String tip){
            boolean over=hover(x,y,w,h);Color bg=primary?t.green:t.elevated;Color fg=primary?new Color(t.light?0xFFFFFF:0x14221A):t.text;
            if(over)bg=blend(bg,t.text,primary?.07:.065);
            rounded(x,y,w,h,12,bg);center(label,x+w/2,y+h/2+5,13,fg,true);hit(x,y,w,h,action,tip);
        }
        void softButton(String label,double x,double y,double w,double h,Color color,Runnable action,String tip){
            rounded(x,y,w,h,12,alpha(color,hover(x,y,w,h)?37:19));center(label,x+w/2,y+h/2+5,12,color,true);hit(x,y,w,h,action,tip);
        }
        void pill(String label,double x,double y,Color color){double w=tw(label,10,true)+18;rounded(x,y,w,22,11,alpha(color,19));text(label,x+9,y+15,10,color,true);}
        void smallCaps(String s,double x,double y){text(s,x,y,10,t.faint,true);}
        Color sign(double v){return v>=0?t.green:t.red;}
        void stat(double x,double y,double w,String label,String value,String note,Color noteColor){
            card(x,y,w,85);text(label,x+18,y+23,11,t.muted,false);text(value,x+18,y+54,26,t.text,true);text(note,x+18,y+73,10,noteColor,false);
        }
        void pageTitle(String title,String subtitle){text(title,cx,126,30,t.text,true);text(subtitle,cx,151,12,t.muted,false);}
        void badge(Stock s,double x,double y,int size){
            Color c=new Color(s.color);rounded(x,y,size,size,Math.max(9,size*.27),alpha(c,t.light?45:30));
            center(s.symbol.substring(0,Math.min(2,s.symbol.length())),x+size/2.0,y+size/2.0+4,size>=40?14:11,t.light?blend(c,Color.BLACK,.45):c,true);
        }
        void icon(String name,double x,double y,Color c,int size){
            Graphics2D old=g;g=(Graphics2D)g.create();g.translate(x,y);g.scale(size/20.0,size/20.0);g.setStroke(new BasicStroke(1.55f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.setColor(c);
            switch(name){
                case "dashboard"->{g.draw(new RoundRectangle2D.Double(1,1,7,7,2,2));g.draw(new RoundRectangle2D.Double(12,1,7,7,2,2));g.draw(new RoundRectangle2D.Double(1,12,7,7,2,2));g.draw(new RoundRectangle2D.Double(12,12,7,7,2,2));}
                case "markets"->{g.drawLine(3,4,3,16);g.drawRect(1,7,4,6);g.drawLine(10,0,10,13);g.drawRect(8,3,4,6);g.drawLine(17,7,17,20);g.drawRect(15,11,4,5);}
                case "portfolio"->{g.draw(new RoundRectangle2D.Double(1,5,18,13,3,3));g.draw(new RoundRectangle2D.Double(6,1,8,5,2,2));g.drawLine(1,10,19,10);g.drawLine(9,10,11,10);}
                case "research"->{g.draw(new Ellipse2D.Double(1,1,12,12));g.drawLine(11,11,18,18);g.drawLine(4,9,7,5);g.drawLine(7,5,10,8);}
                case "private"->{g.draw(new Ellipse2D.Double(1,1,18,18));g.drawLine(10,4,10,16);g.drawLine(5,7,15,7);g.drawLine(5,13,15,13);}
                case "estate"->{g.drawRect(3,1,14,18);g.drawLine(1,19,19,19);for(int i=0;i<3;i++)for(int j=0;j<2;j++)g.drawRect(6+j*5,4+i*4,1,1);}
                case "news"->{g.draw(new RoundRectangle2D.Double(1,2,18,16,3,3));g.drawRect(4,5,5,5);g.drawLine(12,5,16,5);g.drawLine(12,9,16,9);g.drawLine(4,14,16,14);}
                case "goals"->{g.draw(new RoundRectangle2D.Double(5,1,10,12,4,4));g.draw(new Arc2D.Double(0,2,9,8,90,180,Arc2D.OPEN));g.draw(new Arc2D.Double(11,2,9,8,-90,180,Arc2D.OPEN));g.drawLine(10,13,10,18);g.drawLine(6,19,14,19);}
                case "settings"->{g.draw(new Ellipse2D.Double(3,3,14,14));g.draw(new Ellipse2D.Double(7,7,6,6));for(int i=0;i<8;i++){double a=i*Math.PI/4;g.draw(new Line2D.Double(10+7*Math.cos(a),10+7*Math.sin(a),10+10*Math.cos(a),10+10*Math.sin(a)));}}
                case "arrow"->{g.drawLine(3,15,16,3);g.drawLine(6,3,16,3);g.drawLine(16,3,16,13);}
                case "bell"->{g.draw(new Arc2D.Double(4,2,12,16,0,180,Arc2D.OPEN));g.drawLine(4,10,3,15);g.drawLine(16,10,17,15);g.drawLine(3,15,17,15);g.draw(new Arc2D.Double(8,15,4,4,180,180,Arc2D.OPEN));}
                case "save"->{g.draw(new RoundRectangle2D.Double(2,1,16,18,2,2));g.drawRect(6,1,8,6);g.drawRect(6,12,8,7);}
                default->{g.draw(new Ellipse2D.Double(1,1,18,18));g.drawLine(10,5,10,11);g.drawLine(10,15,10,15);}
            }g.dispose();g=old;
        }

        void sidebar(){
            fill(0,0,205,H,t.sidebar);line(204,0,204,H,t.border);
            rounded(24,25,33,33,10,t.green);Color dark=new Color(t.light?0xFFFFFF:0x15261B);g.setColor(dark);g.setStroke(new BasicStroke(2.3f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            Path2D p=new Path2D.Double();p.moveTo(33,48);p.lineTo(40.5,33);p.lineTo(48,48);g.draw(p);g.drawLine(37,43,44,43);
            text("AUREL",69,48,21,t.text,true);smallCaps("CAPITAL SIMULATOR",25,82);
            smallCaps("WORKSPACE",25,128);
            for(int i=0;i<8;i++){
                double y=146+i*49;boolean active=page.equals(PAGES[i]);
                if(active)rounded(13,y-3,179,42,12,t.elevated);else if(hover(13,y-3,179,42))rounded(13,y-3,179,42,12,alpha(t.elevated,110));
                icon(PAGES[i],27,y+8,active?t.green:t.muted,18);text(NAMES[i],59,y+22,13,active?t.text:t.muted,active);
                if(active)rounded(187,y+9,3,14,3,t.green);
                final String target=PAGES[i];hit(13,y-3,179,42,()->navigate(target),"Taste "+(i+1)+" · "+NAMES[i]);
            }
            double y=Math.max(604,H-228);line(24,y,181,y,t.border);
            rounded(24,y+24,33,33,33,alpha(t.accent,22));center("C",40.5,y+46,13,t.accent,true);
            text("COLIN",69,y+37,11,t.text,true);text(game().rank(),69,y+55,10,t.muted,false);
            smallCaps(game().xp()+" XP  /  KARRIERE",25,y+81);
            softButton("Spielanleitung",24,H-115,157,33,t.muted,app::help,"Handel, Forschung, Immobilien und Steuerung erklärt");
            icon("settings",27,H-64,page.equals("settings")?t.green:t.muted,17);text("Einstellungen",58,H-50,12,t.muted,false);hit(20,H-78,168,42,()->navigate("settings"),"Taste 9 · Einstellungen und Spielstände");
        }
        void header(){
            fill(205,0,W-205,79,t.background);line(205,78,W,78,t.border);
            rounded(cx,28,7,7,7,app.paused?t.gold:t.green);text(app.busy?"SIMULIERT …":app.paused?"PAUSIERT":"MARKT LÄUFT",cx+16,36,10,app.paused?t.gold:t.green,true);
            text(game().clock(),cx,60,12,t.muted,false);
            double x=W-555;
            softButton("+1 Tag",x,24,78,33,t.muted,()->app.advanceDays(1),"Bis zum Handelsschluss simulieren und pausieren");
            button(app.paused?"▶  Start":"Ⅱ  Pause",x+88,24,99,33,false,()->{if(!app.busy){app.paused=!app.paused;app.accumulated=0;}},"Leertaste · 1× entspricht fünf Börsenminuten pro Sekunde");
            button(app.speed+"×",x+195,24,51,33,false,()->{app.speed=app.speed==1?4:app.speed==4?16:app.speed==16?64:1;},"Geschwindigkeit: 1×, 4×, 16×, 64×");
            line(x+263,25,x+263,57,t.border);
            button(game().light?"Dunkel":"Hell",x+278,24,66,33,false,()->game().light=!game().light,"Zwischen hellem und dunklem Design wechseln");
            icon("save",x+363,31,t.muted,19);hit(x+351,21,44,40,()->app.save(false),"Strg+S · Spielstand lokal speichern");
            rounded(x+408,25,110,31,15,alpha(t.green,13));center("OFFLINE",x+463,45,10,t.green,true);
        }
        void footer(){
            fill(205,H-36,W-205,36,t.background);line(205,H-36,W,H-36,t.border);
            text("AUREL EXCHANGE",cx,H-13,9,t.muted,true);text("•  SIMULIERTE KURSE  •  KEIN ECHTGELD",cx+112,H-13,9,t.faint,false);
            right("Seed "+game().seed+"  ·  v"+VERSION,W-28,H-13,9,t.faint,false);
        }
        void drawToast(){
            long age=System.currentTimeMillis()-toastAt;if(toast.isEmpty()||age>6500)return;
            double w=Math.min(cw-24,Math.max(330,tw(toast,12,false)+58));double x=cx+(cw-w)/2,y=H-94;
            double f=game().animation?clamp(age/160.0,0,1):1;double slide=(1-f)*10;y+=slide;
            rounded(x-1,y-1,w+2,45,15,alpha(t.border,230));rounded(x,y,w,43,14,t.elevated);
            rounded(x+15,y+18,6,6,6,t.green);text(cut(toast,w-53,12,false),x+30,y+27,12,t.text,false);
            hit(x,y,w,43,()->toast="","Klicken, um Hinweis zu schließen");
        }
        void drawTooltip(){
            if(lastTip.isEmpty()||System.currentTimeMillis()-hoverSince<900||mouseY<0)return;
            double w=Math.min(425,tw(lastTip,11,false)+26),x=clamp(mouseX+12,212,W-w-12),y=Math.min(mouseY+25,H-73);
            rounded(x,y,w,32,9,t.elevated);g.setColor(t.border);g.draw(new RoundRectangle2D.Double(x,y,w,32,9,9));
            text(cut(lastTip,w-24,11,false),x+12,y+21,11,t.text,false);
        }

        // ───────────────────── MARKETS / EXECUTION TICKET ──────────────────

        List<Stock> filteredStocks(){
            ArrayList<Stock> result=new ArrayList<>();String q=searchQuery.toLowerCase(Locale.ROOT);
            for(Stock s:game().stocks.values())if((!onlyWatch||game().watchlist.contains(s.symbol))
                &&(sectorFilter.equals("Alle Sektoren")||SECTORS[s.sector].equals(sectorFilter))
                &&(q.isEmpty()||s.symbol.toLowerCase(Locale.ROOT).contains(q)||s.name.toLowerCase(Locale.ROOT).contains(q)))result.add(s);
            if(sort.equals("Tagesgewinn"))result.sort(Comparator.comparingDouble(Stock::change).reversed());
            if(sort.equals("Tagesverlust"))result.sort(Comparator.comparingDouble(Stock::change));
            if(sort.equals("Name"))result.sort(Comparator.comparing(s->s.name));
            return result;
        }
        void markets(){
            pageScroll=0;hitDy=0;Game gm=game();Stock s=stock();
            pageTitle("Dein nächster Move.","Ein Markt voller Möglichkeiten. Du entscheidest, was daraus wird.");
            softButton("Suchen  ⌕",cx+cw-113,116,113,34,t.muted,this::search,"Strg+F · Unternehmen oder Ticker suchen");
            double sw=(cw-42)/4;
            stat(cx,173,sw,"NETTOVERMÖGEN",eur(gm.wealth()),pct(gm.wealth()/gm.initialCapital-1)+" seit Spielstart",sign(gm.wealth()-gm.initialCapital));
            stat(cx+sw+14,173,sw,"VERFÜGBARES KAPITAL",eur(gm.freeCash()),eur(gm.reservedCash())+" für Orders reserviert",t.muted);
            stat(cx+2*(sw+14),173,sw,"AUREL 24 INDEX",num(gm.index()),pct(gm.index()/1000-1)+" seit Spielstart",sign(gm.index()-1000));
            stat(cx+3*(sw+14),173,sw,"MARKTUMFELD",gm.regimeName(),"Leitzins "+plainPct(gm.rate)+"  ·  Inflation "+plainPct(gm.inflation),t.muted);
            double top=278,bottom=H-57,height=bottom-top;
            double watchW=234,ticketW=270,midX=cx+watchW+16,midW=cw-watchW-ticketW-32,ticketX=cx+cw-ticketW;
            watchlist(cx,top,watchW,height);
            double chartH=height-157;
            card(midX,top,midW,chartH);
            badge(s,midX+19,top+18,37);text(s.name,midX+68,top+37,19,t.text,true);
            text(s.symbol+"  /  "+SECTORS[s.sector]+"  /  EUR",midX+68,top+55,10,t.muted,false);
            double px=midX+midW-20;right(eur(s.price),px,top+38,23,t.text,true);right(pct(s.change())+" heute",px,top+57,11,sign(s.change()),true);
            String[] ranges={"1T","1W","1M","3M","1J"};
            for(int i=0;i<ranges.length;i++){
                String r=ranges[i];double xx=midX+16+i*37;boolean active=range.equals(r);
                if(active)rounded(xx,top+75,33,25,8,t.elevated);
                center(r,xx+16,top+92,10,active?t.text:t.faint,active);hit(xx,top+73,33,28,()->{range=r;chartPan=0;},"Zeitraum "+r+" · Mausrad zoomt, Ziehen verschiebt");
            }
            double options=midX+midW-225;
            chartToggle(candles?"Kerzen":"Linie",options,top+75,55,true,()->candles=!candles,"Zwischen Kerzenchart und Schlusskurslinie wechseln");
            chartToggle(ma==0?"MA aus":ma==1?"SMA20":"EMA20",options+60,top+75,54,ma!=0,()->ma=(ma+1)%3,"20-Perioden-Durchschnitt: SMA / EMA / aus");
            chartToggle("BB",options+119,top+75,30,bollinger,()->bollinger=!bollinger,"Bollinger-Bänder: 20 Perioden, zwei Standardabweichungen");
            chartToggle("Vol",options+154,top+75,30,volume,()->volume=!volume,"Gehandeltes Volumen ein- oder ausblenden");
            chartToggle("Log",options+189,top+75,29,logarithmic,()->logarithmic=!logarithmic,"Logarithmische oder lineare Preisachse");
            drawPriceChart(s,midX+13,top+111,midW-23,chartH-122);
            drawMicrostructure(s,midX,top+chartH+16,midW,141);
            ticket(s,ticketX,top,ticketW,height);
            contentHeight=H-140;
        }
        void chartToggle(String text,double x,double y,double w,boolean active,Runnable action,String tooltip){
            if(active)rounded(x,y,w,25,8,alpha(t.accent,13));center(text,x+w/2,y+17,9,active?t.accent:t.faint,true);hit(x,y,w,26,action,tooltip);
        }
        void watchlist(double x,double y,double w,double h){
            card(x,y,w,h);text(onlyWatch?"Watchlist":"Wertpapiere",x+17,y+28,15,t.text,true);
            String star=onlyWatch?"★":"☆";right(star,x+w-17,y+28,18,onlyWatch?t.gold:t.muted,false);
            hit(x+w-46,y+8,33,31,()->{onlyWatch=!onlyWatch;marketScroll=0;},"Nur deine Favoriten anzeigen");
            button(cut(sectorFilter,w-54,11,false)+"  ▾",x+13,y+43,w-26,30,false,()->{
                List<String> choices=new ArrayList<>();choices.add("Alle Sektoren");choices.addAll(Arrays.asList(SECTORS));app.choose("Sektor",choices,i->{sectorFilter=choices.get(i);marketScroll=0;});
            },"Markt nach Branchen filtern");
            smallCaps(sort.equals("Standard")?"UNTERNEHMEN":sort.toUpperCase(Locale.GERMAN),x+16,y+94);
            right("HEUTE  ▾",x+w-17,y+94,9,t.faint,true);hit(x+10,y+79,w-20,25,()->app.choose("Sortieren",List.of("Standard","Tagesgewinn","Tagesverlust","Name"),i->{sort=List.of("Standard","Tagesgewinn","Tagesverlust","Name").get(i);marketScroll=0;}),"Nach Gewinn, Verlust oder Namen sortieren");
            List<Stock> rows=filteredStocks();int count=Math.max(1,(int)((h-147)/57));marketScroll=Math.min(marketScroll,Math.max(0,rows.size()-count));
            watchBounds=new Rectangle2D.Double(x,y+103,w,h-136);
            for(int i=0;i<count&&i+marketScroll<rows.size();i++){
                Stock s=rows.get(i+marketScroll);double yy=y+104+i*57;boolean active=selected.equals(s.symbol);
                if(active)rounded(x+7,yy,w-14,52,11,t.elevated);else if(hover(x+7,yy,w-14,52))rounded(x+7,yy,w-14,52,11,alpha(t.elevated,130));
                badge(s,x+16,yy+11,29);text(s.symbol,x+55,yy+23,11,t.text,true);
                text(cut(s.name,w-129,9,false),x+55,yy+39,9,t.muted,false);
                right(num(s.price),x+w-15,yy+23,12,t.text,true);right(pct(s.change()),x+w-15,yy+40,9,sign(s.change()),false);
                hit(x+7,yy,w-14,52,()->select(s),s.name+" · "+SECTORS[s.sector]);
            }
            if(rows.isEmpty())wrap("Keine Treffer. Suche oder Sektorfilter zurücksetzen.",x+18,y+144,w-36,12,t.muted,3);
            line(x+14,y+h-34,x+w-14,y+h-34,t.border);
            text(rows.size()+" Werte",x+16,y+h-13,10,t.faint,false);right("↕  Scrollen",x+w-16,y+h-13,10,t.faint,false);
            if(!searchQuery.isEmpty())hit(x+10,y+h-33,w-20,31,()->{searchQuery="";marketScroll=0;},"Suchfilter zurücksetzen: "+searchQuery);
        }
        void drawMicrostructure(Stock s,double x,double y,double w,double h){
            card(x,y,w,h);text("Markttiefe",x+17,y+26,13,t.text,true);pill("MODELL",x+106,y+10,t.muted);
            double split=x+w*.51;line(split,y+15,split,y+h-15,t.border);
            text("GELD",x+17,y+49,9,t.faint,true);right("BRIEF",split-19,y+49,9,t.faint,true);
            int q=Math.max(1,s.liquidity/3);
            for(int i=0;i<3;i++){
                double yy=y+70+i*22;
                rounded(x+13,yy-12,w*.205*(1-i*.16),17,3,alpha(t.green,12));rounded(split-w*.205-12,yy-12,w*.205*(1-i*.16),17,3,alpha(t.red,12));
                text(num(Math.max(.01,s.bid(game())-i*.02)),x+18,yy,11,t.green,false);text(compact(q*(i+1)),x+w*.20,yy,9,t.muted,false);
                right(num(s.ask(game())+i*.02),split-17,yy,11,t.red,false);
            }
            text("Spread",split+18,y+29,11,t.muted,false);right(eur(s.spread(game())),x+w-18,y+29,11,t.text,true);
            text("Volumen heute",split+18,y+54,11,t.muted,false);right(compact(s.sessionVolume),x+w-18,y+54,11,t.text,false);
            text("Nächste Zahlen",split+18,y+79,11,t.muted,false);right(date(s.reportDay).format(SHORT_DATE),x+w-18,y+79,11,t.text,false);
            text("Marktkapitalisierung",split+18,y+104,10,t.muted,false);right(compact(s.marketCap())+" €",x+w-18,y+104,11,t.text,false);
            text("Retail-Fill-Liquidität: "+INTEGER.format(s.liquidity)+" Stück",split+18,y+126,9,t.faint,false);
            hit(x,y,w,h,()->app.dialog("Wie die Ausführung funktioniert","Die drei angezeigten Geld-/Briefstufen sind eine illustrative Markttiefe, kein echtes Börsenorderbuch.\n\nAusführungen nutzen den angezeigten Spread, einen größenabhängigen Preisaufschlag bzw. -abschlag und ein begrenztes Liquiditätsbudget pro Fünf-Minuten-Schritt. Größere Orders können teilweise ausgeführt werden.\n\nLimits werden auch nach Preisaufschlag niemals verletzt. Stops werden erst durch einen beobachteten Simulationskurs ausgelöst. Zwischen den Simulationsschritten liegende Kurswege werden nicht gehandelt.",new String[0],new String[0],"Verstanden",false),"Details zum modellierten Orderbuch und zu Teilausführungen");
        }
        void ticket(Stock s,double x,double y,double w,double h){
            Game gm=game();card(x,y,w,h);text("Order aufgeben",x+18,y+28,15,t.text,true);
            boolean starred=gm.watchlist.contains(s.symbol);
            text(starred?"★":"☆",x+w-41,y+29,18,starred?t.gold:t.muted,false);hit(x+w-51,y+8,37,33,()->{if(starred)gm.watchlist.remove(s.symbol);else gm.watchlist.add(s.symbol);},"Favorit für "+s.symbol+" umschalten");
            rounded(x+15,y+47,w-30,38,11,t.elevated);
            double seg=(w-36)/2;
            rounded(x+18+(buy?0:seg),y+50,seg,32,9,alpha(buy?t.green:t.red,26));
            center("Kaufen",x+18+seg/2,y+71,12,buy?t.green:t.muted,true);center("Verkaufen",x+18+seg*1.5,y+71,12,buy?t.muted:t.red,true);
            hit(x+15,y+47,(w-30)/2,38,()->{buy=true;if(orderType.equals("TRAILING"))orderType="MARKET";},"Aktien kaufen");
            hit(x+w/2,y+47,(w-30)/2,38,()->buy=false,"Vorhandene, unreservierte Aktien verkaufen");
            text("ORDERTYP",x+18,y+111,9,t.faint,true);
            button(orderLabel(orderType)+"  ▾",x+15,y+120,w-30,35,false,()->{
                List<String> types=buy?List.of("MARKET","LIMIT","STOP","STOP-LIMIT"):List.of("MARKET","LIMIT","STOP","STOP-LIMIT","TRAILING");
                app.choose("Ordertyp",types.stream().map(Board::orderLabel).toList(),i->orderType=types.get(i));
            },"Market, Limit, Stop, Stop-Limit oder Trailing-Stop");
            text("STÜCKZAHL",x+18,y+181,9,t.faint,true);
            button("−",x+15,y+191,34,37,false,()->quantity=Math.max(1,quantity-1),"Eine Aktie weniger");
            button(INTEGER.format(quantity),x+56,y+191,w-112,37,false,()->{
                Double n=app.inputNumber("Stückzahl",buy?"Wie viele Aktien möchtest du kaufen?":"Verfügbar: "+gm.availableShares(s.symbol)+" Stück.","Ganze Aktien",quantity);
                if(n!=null){if(n<1||n>10000000||n!=Math.rint(n))throw new IllegalArgumentException("Bitte eine ganze Stückzahl von 1 bis 10.000.000 eingeben.");quantity=n.intValue();}
            },"Stückzahl direkt eingeben");
            button("+",x+w-49,y+191,34,37,false,()->quantity=Math.min(10000000,quantity+1),"Eine Aktie mehr");
            for(int i=0;i<3;i++){
                int part=new int[]{25,50,100}[i];softButton(part+" %",x+15+i*(w-26)/3,y+237,(w-42)/3,24,t.muted,()->quantity=Math.max(1,(int)Math.floor((buy?gm.maxBuy(s.symbol):gm.availableShares(s.symbol))*part/100.0)),"Anteil des freien Guthabens / verfügbaren Bestands");
            }
            double fieldY=y+276;
            if(orderType.equals("LIMIT")||orderType.equals("STOP-LIMIT")){
                double value=limitPrice>0?limitPrice:s.price;
                ticketField("Limit",eur(value),x,fieldY,w,()->{Double v=app.inputNumber("Limitpreis","Kauf: höchstens dieser Preis. Verkauf: mindestens dieser Preis. Eine Ausführung ist nicht garantiert.","Limit in Euro",value);if(v!=null){if(v<=0)throw new IllegalArgumentException("Limit muss positiv sein.");limitPrice=v;}});fieldY+=40;
            }
            if(orderType.equals("STOP")||orderType.equals("STOP-LIMIT")){
                double value=stopPrice>0?stopPrice:cents(s.price*(buy?1.025:.975));
                ticketField("Stop",eur(value),x,fieldY,w,()->{Double v=app.inputNumber("Stoppreis","Beim Erreichen wird die Order aktiviert. Ein Stop garantiert keinen Ausführungspreis. Bei Kurslücken kann die Ausführung deutlich abweichen.","Stop in Euro",value);if(v!=null){if(v<=0)throw new IllegalArgumentException("Stop muss positiv sein.");stopPrice=v;}});fieldY+=40;
            }
            if(orderType.equals("TRAILING")){
                ticketField("Trailing-Abstand",plainPct(trail),x,fieldY,w,()->{Double v=app.inputNumber("Trailing-Stop","Der Stop folgt steigenden Kursen nach oben. Bei fallendem Kurs bleibt er stehen. Gültig: 0,1 bis 50 %.","Abstand in Prozent",trail*100);if(v!=null){if(v<.1||v>50)throw new IllegalArgumentException("Abstand muss 0,1 bis 50 % betragen.");trail=v/100;}});fieldY+=40;
            }
            ticketField("Gültigkeit",tif.equals("DAY")?"Tagesgültig  ▾":"Bis Widerruf  ▾",x,fieldY,w,()->app.choose("Gültigkeit",List.of("DAY · bis Handelsschluss","GTC · bis Widerruf"),i->tif=i==0?"DAY":"GTC"));fieldY+=42;
            double footer=y+h-150;
            if(fieldY>footer-9)footer=fieldY+5;
            // The smallest supported logical height leaves space for both Stop and Limit.
            line(x+17,footer,x+w-17,footer,t.border);
            double indicative=gm.executionPrice(s,buy,Math.min(quantity,Math.max(1,s.liquidity))),notional=quantity*indicative,f=gm.fee(notional,true);
            text("Kursindikator",x+18,footer+23,11,t.muted,false);right(eur(indicative),x+w-18,footer+23,11,t.text,false);
            text("Geschätzte Gebühr",x+18,footer+46,10,t.muted,false);right(eur(f),x+w-18,footer+46,11,t.text,false);
            text(buy?"Vorauss. Kaufwert":"Vorauss. Erlös",x+18,footer+73,11,t.muted,false);right(eur(notional+(buy?f:-f)),x+w-18,footer+73,16,t.text,true);
            button(buy?"Kauf prüfen  →":"Verkauf prüfen  →",x+15,footer+89,w-30,39,buy,()->reviewOrder(s),"Order prüfen und anschließend verbindlich im Spiel abschicken");
            String info=buy?"Kein Echtgeld. Keine Nachschusspflicht.":gm.availableShares(s.symbol)+" Aktien frei verfügbar.";
            center(info,x+w/2,footer+144,9,t.faint,false);
            // Alarm is available from the chart details as a persistent keyboard-free action.
        }
        void ticketField(String label,String value,double x,double y,double w,Runnable action){
            text(label,x+18,y+19,10,t.muted,false);right(value,x+w-21,y+19,11,t.text,true);
            hit(x+15,y,w-30,31,action,label+" bearbeiten");if(hover(x+15,y,w-30,31))line(x+16,y+30,x+w-16,y+30,t.accent);
        }
        static String orderLabel(String type){return switch(type){case "MARKET"->"Market";case "LIMIT"->"Limit";case "STOP"->"Stop";case "STOP-LIMIT"->"Stop-Limit";default->"Trailing-Stop";};}
        void reviewOrder(Stock s){
            Game gm=game();double l=limitPrice>0?limitPrice:s.price,st=stopPrice>0?stopPrice:cents(s.price*(buy?1.025:.975));
            double estimated=gm.executionPrice(s,buy,Math.min(quantity,Math.max(1,s.liquidity)));
            String body=(buy?"Kauf":"Verkauf")+" von "+INTEGER.format(quantity)+" Aktien von "+s.name+" ("+s.symbol+").\n\n"+
                "Typ: "+orderLabel(orderType)+" · "+(tif.equals("DAY")?"bis Handelsschluss":"bis Widerruf")+"\n"+
                ((orderType.equals("LIMIT")||orderType.equals("STOP-LIMIT"))?"Limit: "+eur(l)+"\n":"")+
                ((orderType.equals("STOP")||orderType.equals("STOP-LIMIT"))?"Stop: "+eur(st)+"\n":"")+
                (orderType.equals("TRAILING")?"Trailing-Abstand: "+plainPct(trail)+"\n":"")+
                "Indikativer Ausführungskurs: "+eur(estimated)+"\nGebühr: 0,95 € je Order + "+String.format(Locale.GERMANY,"%.2f %%",gm.feeRate()*100)+" vom ausgeführten Wert.\n\n"+
                "Der tatsächliche Preis kann sich ändern. Große Orders können teilweise ausgeführt werden. Kauforders reservieren Guthaben; Market- und Stopkäufe enthalten 4 % Preisreserve. Es wird ausschließlich Spielgeld verwendet.";
            app.confirm(buy?"Kauforder bestätigen":"Verkaufsorder bestätigen",body,()->{
                Order o=gm.submit(s.symbol,buy,orderType,quantity,l,st,trail,tif);app.showToast("Order #"+o.id+" · "+o.status.toLowerCase(Locale.GERMAN));
            });
        }

        // ───────────────────── CANDLE CHART / INDICATORS ───────────────────

        List<Candle> chartData(Stock s){
            ArrayList<Candle> out=new ArrayList<>();
            if(range.equals("1T")){for(Candle c:s.intraday)if(c.day==game().day)out.add(c);}
            else if(range.equals("1W")){
                Candle current=null;int key=Integer.MIN_VALUE;
                for(Candle c:s.intraday)if(c.day>=game().day-4){
                    int k=c.day*100+(c.slot-1)/3;
                    if(k!=key){current=c.copy();out.add(current);key=k;}
                    else{current.high=Math.max(current.high,c.high);current.low=Math.min(current.low,c.low);current.close=c.close;current.volume+=c.volume;current.slot=c.slot;}
                }
            }else{
                int days=range.equals("1M")?22:range.equals("3M")?66:252;
                int from=Math.max(0,s.daily.size()-days);for(int i=from;i<s.daily.size();i++)out.add(s.daily.get(i));
            }
            if(out.isEmpty())out.add(s.last());return out;
        }
        double[] indicator(List<Candle> source,boolean ema){
            double[] result=new double[source.size()];double sum=0,last=source.get(0).close;
            for(int i=0;i<source.size();i++){
                if(ema){last=i==0?source.get(i).close:source.get(i).close*(2.0/21)+last*(19.0/21);result[i]=last;}
                else{sum+=source.get(i).close;if(i>=20)sum-=source.get(i-20).close;result[i]=sum/Math.min(i+1,20);}
            }return result;
        }
        double[] deviations(List<Candle> source,double[] averages){
            double[] r=new double[source.size()];for(int i=0;i<source.size();i++){double sum=0;int n=Math.min(20,i+1);for(int j=i-n+1;j<=i;j++)sum+=Math.pow(source.get(j).close-averages[i],2);r[i]=Math.sqrt(sum/n);}return r;
        }
        void drawPriceChart(Stock s,double x,double y,double w,double h){
            List<Candle> source=chartData(s);int count=Math.min(visibleCandles,source.size());
            chartPan=Math.min(chartPan,Math.max(0,source.size()-count));int end=source.size()-chartPan,start=end-count;
            List<Candle> bars=source.subList(start,end);
            double[] moving=indicator(source,ma==2),sma=indicator(source,false),dev=deviations(source,sma);
            double low=Double.POSITIVE_INFINITY,high=0;long maxVolume=1;
            for(int i=start;i<end;i++){
                Candle c=source.get(i);low=Math.min(low,c.low);high=Math.max(high,c.high);maxVolume=Math.max(maxVolume,c.volume);
                if(bollinger){low=Math.min(low,Math.max(.01,sma[i]-dev[i]*2));high=Math.max(high,sma[i]+dev[i]*2);}
            }
            double extra=Math.max(high*.006,(high-low)*.16);low=Math.max(.01,low-extra);high+=extra;
            final double lo=logarithmic?Math.log(low):low,hi=logarithmic?Math.log(high):high;
            double plotX=x+6,plotY=y+35,plotW=w-70,volumeH=volume?42:0,plotH=Math.max(85,h-77-volumeH);
            final double py=plotY,ph=plotH;
            DoubleUnaryOperator priceY=p->py+(hi-(logarithmic?Math.log(Math.max(.001,p)):p))/(hi-lo)*ph;
            chartBounds=new Rectangle2D.Double(plotX,plotY,plotW,plotH+volumeH);
            double candleW=plotW/count;
            boolean inside=chartBounds.contains(mouseX,mouseY);int hoverIndex=inside?(int)clamp((mouseX-plotX)/candleW,0,count-1):count-1;
            Candle selectedBar=bars.get(hoverIndex);
            String values="O "+num(selectedBar.open)+"    H "+num(selectedBar.high)+"    L "+num(selectedBar.low)+"    C "+num(selectedBar.close);
            text(cut(values,w-24,9,false),x+7,y+16,9,t.muted,false);
            Shape clip=g.getClip();g.clip(new Rectangle2D.Double(plotX,plotY,plotW,plotH+volumeH+3));
            for(int i=0;i<5;i++){
                double yy=plotY+i*plotH/4;g.setColor(alpha(t.border,140));g.setStroke(new BasicStroke(1,0,0,10,new float[]{2,5},0));g.draw(new Line2D.Double(plotX,yy,plotX+plotW,yy));
            }
            for(int i=0;i<5;i++){
                double xx=plotX+plotW*i/4;g.setColor(alpha(t.border,130));g.setStroke(new BasicStroke(1,0,0,10,new float[]{2,5},0));g.draw(new Line2D.Double(xx,plotY,xx,plotY+plotH+volumeH));
            }
            if(bollinger){
                Path2D band=new Path2D.Double();
                for(int i=0;i<count;i++){double xx=plotX+(i+.5)*candleW,yy=priceY.applyAsDouble(sma[start+i]+2*dev[start+i]);if(i==0)band.moveTo(xx,yy);else band.lineTo(xx,yy);}
                for(int i=count-1;i>=0;i--)band.lineTo(plotX+(i+.5)*candleW,priceY.applyAsDouble(Math.max(.01,sma[start+i]-2*dev[start+i])));
                band.closePath();g.setColor(alpha(t.accent,14));g.fill(band);g.setColor(alpha(t.accent,55));g.setStroke(new BasicStroke(1));g.draw(band);
            }
            Path2D pricePath=new Path2D.Double();
            for(int i=0;i<count;i++){
                Candle c=bars.get(i);double xx=plotX+(i+.5)*candleW;Color color=c.close>=c.open?t.green:t.red;
                if(candles){
                    g.setColor(alpha(color,200));g.setStroke(new BasicStroke(1));g.draw(new Line2D.Double(xx,priceY.applyAsDouble(c.high),xx,priceY.applyAsDouble(c.low)));
                    double top=Math.min(priceY.applyAsDouble(c.open),priceY.applyAsDouble(c.close)),bodyH=Math.max(1.5,Math.abs(priceY.applyAsDouble(c.open)-priceY.applyAsDouble(c.close)));
                    double bodyW=Math.max(2,candleW*.61);rounded(xx-bodyW/2,top,bodyW,bodyH,Math.min(2,bodyW*.2),color);
                }
                double closeY=priceY.applyAsDouble(c.close);if(i==0)pricePath.moveTo(xx,closeY);else pricePath.lineTo(xx,closeY);
                if(volume){double vh=c.volume/(double)maxVolume*(volumeH-9);rounded(xx-Math.max(2,candleW*.58)/2,plotY+plotH+volumeH-vh,Math.max(2,candleW*.58),vh,1,alpha(color,65));}
            }
            if(!candles){
                Path2D area=(Path2D)pricePath.clone();area.lineTo(plotX+(count-.5)*candleW,plotY+plotH);area.lineTo(plotX+candleW/2,plotY+plotH);area.closePath();
                Paint previous=g.getPaint();g.setPaint(new GradientPaint(0,(float)plotY,alpha(t.green,43),0,(float)(plotY+plotH),alpha(t.green,0)));g.fill(area);g.setPaint(previous);
                g.setColor(t.green);g.setStroke(new BasicStroke(2));g.draw(pricePath);
            }
            if(ma!=0){Path2D path=new Path2D.Double();for(int i=0;i<count;i++){double xx=plotX+(i+.5)*candleW,yy=priceY.applyAsDouble(moving[start+i]);if(i==0)path.moveTo(xx,yy);else path.lineTo(xx,yy);}g.setColor(alpha(t.accent,210));g.setStroke(new BasicStroke(1.5f));g.draw(path);}
            // The average purchase price is distinct from the current quote.
            Position position=game().positions.get(s.symbol);
            if(position!=null&&position.quantity>0){
                double yy=priceY.applyAsDouble(position.average);g.setStroke(new BasicStroke(1,0,0,10,new float[]{6,5},0));g.setColor(t.gold);g.draw(new Line2D.Double(plotX,yy,plotX+plotW,yy));
                text("Ø Kauf "+num(position.average),plotX+6,yy-5,9,t.gold,false);
            }
            int drawn=0;
            for(Order o:game().orders)if(o.active()&&o.symbol.equals(s.symbol)&&drawn++<5){
                double value=(o.type.equals("LIMIT")||o.type.equals("STOP-LIMIT")&&o.activated)?o.limit:o.stop;
                if(value<=0)continue;double yy=priceY.applyAsDouble(value);
                g.setStroke(new BasicStroke(1,0,0,10,new float[]{3,4},0));g.setColor(alpha(t.gold,130));g.draw(new Line2D.Double(plotX,yy,plotX+plotW,yy));
                text("#"+o.id+" "+orderLabel(o.type),plotX+6,yy-5,9,t.gold,false);
            }
            double currentY=priceY.applyAsDouble(s.price);
            g.setStroke(new BasicStroke(1,0,0,10,new float[]{4,4},0));g.setColor(alpha(sign(s.change()),130));g.draw(new Line2D.Double(plotX,currentY,plotX+plotW,currentY));
            if(inside){
                double xx=plotX+(hoverIndex+.5)*candleW;
                g.setStroke(new BasicStroke(1,0,0,10,new float[]{3,3},0));g.setColor(alpha(t.text,100));g.draw(new Line2D.Double(xx,plotY,xx,plotY+plotH+volumeH));
                g.draw(new Line2D.Double(plotX,mouseY,plotX+plotW,mouseY));
            }
            g.setClip(clip);
            for(int i=0;i<5;i++){
                double value=hi-(hi-lo)*i/4;if(logarithmic)value=Math.exp(value);
                right(num(value),x+w-2,plotY+i*plotH/4+4,9,t.faint,false);
            }
            if(currentY>=plotY&&currentY<=plotY+plotH){
                rounded(plotX+plotW+3,currentY-10,60,21,5,sign(s.change()));center(num(s.price),plotX+plotW+33,currentY+4,9,new Color(t.light?0xFFFFFF:0x14211A),true);
            }
            for(int i=0;i<4;i++){
                int index=(int)((count-1)*i/3.0);Candle c=bars.get(index);
                String label=range.equals("1T")?time(c.slot):date(c.day).format(SHORT_DATE);
                double xx=plotX+(index+.5)*candleW;
                text(label,clamp(xx-tw(label,9,false)/2,plotX,plotX+plotW-tw(label,9,false)),plotY+plotH+volumeH+17,9,t.faint,false);
            }
            String interval=range.equals("1T")?"5 MIN":range.equals("1W")?"15 MIN":"1 TAG";
            smallCaps(interval+"  ·  "+(logarithmic?"LOG":"EUR")+(chartPan>0?"  ·  HISTORISCH":""),x+6,y+h-3);
            if(inside){
                String when=date(selectedBar.day).format(SHORT_DATE)+" "+(range.equals("1T")||range.equals("1W")?time(selectedBar.slot):"");
                right(when,plotX+plotW-118,y+h-3,9,t.muted,false);
            }
            icon("bell",x+w-99,y+h-16,t.muted,14);hit(x+w-109,y+h-24,31,27,()->{
                Double v=app.inputNumber("Kursalarm · "+s.symbol,"Du wirst informiert, sobald der Kurs das Ziel erreicht. Alle Alarme findest du im Portfolio.","Zielkurs in Euro",s.price*1.05);
                if(v!=null){game().addAlert(s.symbol,v);app.showToast("Kursalarm gesetzt: "+s.symbol+" bei "+eur(v));}
            },"Kursalarm für "+s.symbol+" setzen");
            text("↺ Reset",x+w-70,y+h-3,9,t.muted,false);hit(x+w-74,y+h-24,72,27,()->{chartPan=0;visibleCandles=74;},"Chart zurücksetzen · alternativ Doppelklick im Chart");
        }

        void sparkline(List<Double> values,double x,double y,double w,double h,Color color,boolean area){
            if(values.isEmpty())return;List<Double> data=values.size()==1?List.of(values.get(0),values.get(0)):values;
            double min=Collections.min(data),max=Collections.max(data);double pad=Math.max((max-min)*.18,Math.max(.01,Math.abs(max)*.001));min-=pad;max+=pad;
            Path2D path=new Path2D.Double();
            for(int i=0;i<data.size();i++){double xx=x+w*i/(data.size()-1.0),yy=y+h*(max-data.get(i))/(max-min);if(i==0)path.moveTo(xx,yy);else path.lineTo(xx,yy);}
            if(area){Path2D fill=(Path2D)path.clone();fill.lineTo(x+w,y+h);fill.lineTo(x,y+h);fill.closePath();Paint old=g.getPaint();g.setPaint(new GradientPaint(0,(float)y,alpha(color,45),0,(float)(y+h),alpha(color,0)));g.fill(fill);g.setPaint(old);}
            g.setColor(color);g.setStroke(new BasicStroke(area?2.1f:1.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.draw(path);
        }

        // ───────────────────── OVERVIEW ────────────────────────────────────

        void dashboard(){
            Game gm=game();pageTitle("Dein Kapital. Dein Spiel.","Der Überblick über alles, was du aufbaust.");
            double sw=(cw-42)/4;
            stat(cx,176,sw,"NETTOVERMÖGEN",eur(gm.wealth()),"Nach Krediten, inklusive gebundener Werte",t.muted);
            stat(cx+sw+14,176,sw,"GESAMTRENDITE",pct(gm.wealth()/gm.initialCapital-1),eur(gm.wealth()-gm.initialCapital)+" gegenüber Start",sign(gm.wealth()-gm.initialCapital));
            stat(cx+2*(sw+14),176,sw,"FREIES GUTHABEN",eur(gm.freeCash()),gm.activeOrders()+" offene Orders",t.muted);
            stat(cx+3*(sw+14),176,sw,"ERHALTENE ERTRÄGE",eur(gm.dividends+gm.interest),"Dividenden + Kontozinsen",t.green);
            double leftW=cw*.66-8,rightX=cx+leftW+16,rightW=cw-leftW-16;
            card(cx,279,leftW,292);text("Vermögensentwicklung",cx+22,310,17,t.text,true);text("Dein Portfolio",cx+22,336,10,t.green,true);text("AUREL 24",cx+116,336,10,t.accent,false);
            text(eur(gm.wealth()),cx+22,376,31,t.text,true);
            drawEquityChart(cx+22,392,leftW-44,137);
            text("Start: "+date(0).format(SHORT_DATE),cx+22,552,10,t.faint,false);right("Tag "+gm.day+"  ·  Max. Drawdown "+plainPct(gm.maxDrawdown),cx+leftW-22,552,10,t.faint,false);
            card(rightX,279,rightW,292);text("Deine Allokation",rightX+21,310,16,t.text,true);
            double[] values={gm.cash,gm.stockValue(),gm.privateValue(),Math.max(0,gm.propertyValue())};
            String[] names={"Liquidität","Aktien","Private Equity","Immobilien netto"};Color[] colors={t.green,t.accent,t.gold,new Color(0x8DBBCC)};
            double sum=Arrays.stream(values).sum(),angle=90;
            for(int i=0;i<values.length;i++)if(values[i]>0){double extent=values[i]/Math.max(1,sum)*360;g.setColor(colors[i]);g.setStroke(new BasicStroke(17));g.draw(new Arc2D.Double(rightX+23,344,126,126,angle,-extent,Arc2D.OPEN));angle-=extent;}
            center("ASSETS",rightX+86,404,9,t.muted,true);center(Integer.toString((int)Arrays.stream(values).filter(v->v>0).count()),rightX+86,434,27,t.text,true);
            for(int i=0;i<4;i++){double yy=360+i*34;rounded(rightX+178,yy-7,7,7,3,colors[i]);text(names[i],rightX+192,yy,10,t.muted,false);right(plainPct(values[i]/Math.max(1,sum)),rightX+rightW-20,yy+16,11,t.text,true);}
            line(rightX+21,505,rightX+rightW-21,505,t.border);text("Kredite",rightX+21,535,11,t.muted,false);right(eur(gm.totalDebt()),rightX+rightW-21,535,13,t.text,true);
            double by=590,bh=229,third=(cw-32)/3;
            card(cx,by,third,bh);text("Im Fokus",cx+19,by+29,15,t.text,true);softButton("Märkte →",cx+third-103,by+13,85,26,t.muted,()->navigate("markets"),"Zum Markt wechseln");
            ArrayList<Stock> movers=new ArrayList<>(gm.stocks.values());movers.sort(Comparator.comparingDouble((Stock s)->Math.abs(s.change())).reversed());
            for(int i=0;i<4;i++){
                Stock s=movers.get(i);double yy=by+49+i*43;badge(s,cx+18,yy+2,29);text(s.symbol,cx+59,yy+16,11,t.text,true);text(SECTORS[s.sector],cx+59,yy+32,9,t.muted,false);
                sparkline(s.daily.subList(s.daily.size()-12,s.daily.size()).stream().map(c->c.close).toList(),cx+third-158,yy+9,56,23,sign(s.change()),false);
                right(pct(s.change()),cx+third-18,yy+23,11,sign(s.change()),true);hit(cx+10,yy-2,third-20,42,()->{select(s);navigate("markets");},s.name);
            }
            double bx=cx+third+16;card(bx,by,third,bh);text("AUREL Wire",bx+19,by+29,15,t.text,true);pill("SIMULIERT",bx+third-94,by+13,t.muted);
            for(int i=0;i<Math.min(3,gm.news.size());i++){
                News n=gm.news.get(i);double yy=by+58+i*54;text(n.category,bx+19,yy,9,n.sentiment>0?t.green:n.sentiment<0?t.red:t.accent,true);
                text(cut(n.title,third-39,11,false),bx+19,yy+20,11,t.text,false);hit(bx+10,yy-13,third-20,47,()->showNews(n),n.title);
            }
            double gx=bx+third+16;card(gx,by,third,bh);text("Dein nächster Meilenstein",gx+19,by+29,15,t.text,true);
            int row=0;for(String[] goal:Game.GOALS){if(gm.achievements.contains(goal[0]))continue;if(row>=3)break;
                double yy=by+60+row*53;rounded(gx+20,yy-10,23,23,8,t.elevated);center(Integer.toString(row+1),gx+31.5,yy+6,10,t.muted,true);
                text(goal[1],gx+56,yy,11,t.text,true);text(cut(goal[2],third-79,9,false),gx+56,yy+18,9,t.muted,false);row++;
            }
            hit(gx+10,by+45,third-20,bh-55,()->navigate("goals"),"Alle Meilensteine und deinen Rang anzeigen");contentHeight=850;
        }
        void drawEquityChart(double x,double y,double w,double h){
            List<EquityPoint> all=game().equity;int start=Math.max(0,all.size()-400);List<EquityPoint> data=all.subList(start,all.size());
            double low=Double.POSITIVE_INFINITY,high=0;
            for(EquityPoint p:data){low=Math.min(low,Math.min(p.wealth,p.benchmark));high=Math.max(high,Math.max(p.wealth,p.benchmark));}
            double padding=Math.max((high-low)*.12,game().initialCapital*.008);low-=padding;high+=padding;
            for(int i=0;i<3;i++)line(x,y+i*h/2,x+w,y+i*h/2,alpha(t.border,150));
            for(int kind=1;kind>=0;kind--){
                Path2D path=new Path2D.Double();int n=Math.max(2,data.size());
                for(int i=0;i<n;i++){EquityPoint p=data.get(Math.min(i,data.size()-1));double v=kind==0?p.wealth:p.benchmark;double xx=x+w*i/(n-1.0),yy=y+h*(high-v)/(high-low);if(i==0)path.moveTo(xx,yy);else path.lineTo(xx,yy);}
                Color c=kind==0?t.green:t.accent;
                if(kind==0){Path2D area=(Path2D)path.clone();area.lineTo(x+w,y+h);area.lineTo(x,y+h);area.closePath();Paint previous=g.getPaint();g.setPaint(new GradientPaint(0,(float)y,alpha(c,40),0,(float)(y+h),alpha(c,0)));g.fill(area);g.setPaint(previous);}
                g.setColor(c);g.setStroke(kind==0?new BasicStroke(2.2f):new BasicStroke(1.4f,0,0,10,new float[]{4,4},0));g.draw(path);
            }
            if(data.size()<3)center("Starte die Simulation, um deine Entwicklung zu sehen.",x+w/2,y+h-14,10,t.faint,false);
        }

        // ───────────────────── PORTFOLIO / ORDERS / JOURNALS ────────────────

        void portfolio(){
            Game gm=game();pageTitle("Alles unter deiner Kontrolle.","Positionen, Orders und jeder einzelne Cashflow. Ohne versteckte Zahlen.");
            softButton("Trades exportieren",cx+cw-154,116,154,33,t.muted,app::exportTrades,"Trading-Journal als UTF-8 CSV speichern");
            double sw=(cw-42)/4;
            stat(cx,176,sw,"AKTIENWERT",eur(gm.stockValue()),gm.positions.values().stream().filter(p->p.quantity>0).count()+" aktive Positionen",t.muted);
            stat(cx+sw+14,176,sw,"UNREALISIERT",eur(gm.unrealized()),"Kurswert minus Einstand inklusive Kaufgebühr",sign(gm.unrealized()));
            stat(cx+2*(sw+14),176,sw,"REALISIERT",eur(gm.realized),"Verkäufe nach Gebühren · ohne Spielsteuer",sign(gm.realized));
            stat(cx+3*(sw+14),176,sw,"GEBÜHREN GESAMT",eur(gm.totalFees),"Aktienhandel · "+gm.fills.size()+" Ausführungen",t.muted);
            String[] tabs={"Positionen","Orders","Journal","Cashflows","Alarme"};double tx=cx;
            for(String tab:tabs){double width=tw(tab,12,true)+36;boolean active=portfolioTab.equals(tab);if(active)rounded(tx,281,width,34,10,t.elevated);
                center(tab,tx+width/2,303,12,active?t.text:t.muted,active);hit(tx,281,width,34,()->{portfolioTab=tab;pageScroll=0;},tab+" anzeigen");tx+=width+6;}
            double y=333;
            switch(portfolioTab){
                case "Positionen"->positionTable(y);
                case "Orders"->orderTable(y);
                case "Journal"->fillTable(y);
                case "Cashflows"->cashflowTable(y);
                default->alertTable(y);
            }
        }
        void empty(double y,String title,String description,Runnable action,String button){
            card(cx,y,cw,269);icon("portfolio",cx+cw/2-17,y+37,t.faint,34);center(title,cx+cw/2,y+106,21,t.text,true);
            center(description,cx+cw/2,y+137,12,t.muted,false);button(button,cx+cw/2-91,y+166,182,38,true,action,button);contentHeight=y+300-90;
        }
        void tableLabel(String label,double fraction,double y){smallCaps(label,cx+20+(cw-40)*fraction,y);}
        void positionTable(double y){
            Game gm=game();List<String> syms=gm.positions.entrySet().stream().filter(e->e.getValue().quantity>0).map(Map.Entry::getKey).toList();
            if(syms.isEmpty()){empty(y,"Dein Portfolio wartet auf den ersten Kauf.","Öffne einen Wert, lege die Stückzahl fest und prüfe deine Order.",()->navigate("markets"),"Zu den Märkten →");return;}
            double height=62+syms.size()*66;card(cx,y,cw,height);
            tableLabel("UNTERNEHMEN",0,y+28);tableLabel("STÜCK",.29,y+28);tableLabel("EINSTAND / STK.",.40,y+28);tableLabel("KURS",.56,y+28);tableLabel("MARKTWERT",.69,y+28);tableLabel("GEWINN / VERLUST",.84,y+28);
            for(int i=0;i<syms.size();i++){
                Stock s=gm.stocks.get(syms.get(i));Position p=gm.positions.get(s.symbol);double yy=y+45+i*66;
                if(hover(cx+8,yy,cw-16,62))rounded(cx+8,yy,cw-16,62,10,t.elevated);
                badge(s,cx+19,yy+12,36);text(s.name,cx+68,yy+29,13,t.text,true);text(s.symbol+" · "+SECTORS[s.sector],cx+68,yy+47,10,t.muted,false);
                text(INTEGER.format(p.quantity),cx+20+(cw-40)*.29,yy+35,13,t.text,true);
                text(eur(p.average),cx+20+(cw-40)*.40,yy+35,13,t.muted,false);text(eur(s.price),cx+20+(cw-40)*.56,yy+35,13,t.text,false);
                text(eur(p.quantity*s.price),cx+20+(cw-40)*.69,yy+35,13,t.text,true);
                double pnl=p.quantity*(s.price-p.average);text(eur(pnl),cx+20+(cw-40)*.84,yy+27,13,sign(pnl),true);text(pct(s.price/p.average-1),cx+20+(cw-40)*.84,yy+45,10,sign(pnl),false);
                hit(cx+9,yy,cw-18,61,()->{select(s);navigate("markets");},"Position in "+s.name+" im Markt öffnen");
            }
            double total=gm.stockValue(),yy=y+height+20;card(cx,yy,cw,109);text("Sektor-Risiko",cx+19,yy+27,14,t.text,true);
            double x=cx+19,barW=cw-38;
            for(int sector=0;sector<SECTORS.length;sector++){
                int se=sector;double value=gm.positions.entrySet().stream().filter(e->gm.stocks.get(e.getKey()).sector==se).mapToDouble(e->e.getValue().quantity*gm.stocks.get(e.getKey()).price).sum();
                if(value<=0)continue;double width=value/Math.max(1,total)*barW;Color c=sectorColor(sector);rounded(x,yy+42,Math.max(1,width-3),16,5,c);hit(x,yy+39,width,27,()->{},SECTORS[sector]+": "+plainPct(value/total)+" des Aktienvermögens");x+=width;
            }
            text("Eine hohe Konzentration bedeutet, dass einzelne Branchen dein Ergebnis besonders stark bestimmen.",cx+19,yy+88,11,t.muted,false);contentHeight=yy+134-90;
        }
        Color sectorColor(int sector){return new Color(new int[]{0xB6ACED,0x91B8D2,0xB4D3B5,0xDFC58D,0xD3AF9F,0xC6A9C7,0x99C9C6,0xB8BFC8}[sector]);}
        void orderTable(double y){
            Game gm=game();softButton(openOnly?"Nur offene Orders":"Alle Orders",cx+cw-164,282,164,32,t.muted,()->{openOnly=!openOnly;pageScroll=0;},"Offene oder auch abgeschlossene Orders anzeigen");
            List<Order> list=gm.orders.stream().filter(o->!openOnly||o.active()).limit(350).toList();
            if(list.isEmpty()){empty(y,openOnly?"Keine offenen Orders.":"Noch keine Orderhistorie.","Limit- und Stoporders warten hier auf ihre Ausführung.",()->navigate("markets"),"Order aufgeben →");return;}
            card(cx,y,cw,58+list.size()*71);
            tableLabel("ORDER / WERT",0,y+28);tableLabel("TYP",.21,y+28);tableLabel("STÜCK",.34,y+28);tableLabel("LIMIT / STOP",.46,y+28);tableLabel("RESERVE / Ø FILL",.62,y+28);tableLabel("STATUS",.81,y+28);
            for(int i=0;i<list.size();i++){
                Order o=list.get(i);double yy=y+42+i*71;
                if(i>0)line(cx+19,yy,cx+cw-19,yy,t.border);
                text(o.symbol+"  "+(o.buy?"KAUF":"VERKAUF"),cx+20,yy+26,12,o.buy?t.green:t.red,true);text("#"+o.id+" · "+timestamp(o.createdTick),cx+20,yy+47,10,t.muted,false);
                text(orderLabel(o.type),cx+20+(cw-40)*.21,yy+26,12,t.text,true);text(o.tif+(o.activated?" · ausgelöst":""),cx+20+(cw-40)*.21,yy+47,10,t.muted,false);
                text(o.filled()+" / "+o.quantity,cx+20+(cw-40)*.34,yy+26,12,t.text,true);text(o.remaining+" offen",cx+20+(cw-40)*.34,yy+47,10,t.muted,false);
                text(o.limit>0&&(o.type.equals("LIMIT")||o.type.equals("STOP-LIMIT"))?eur(o.limit):"—",cx+20+(cw-40)*.46,yy+26,12,t.text,false);
                text(o.stop>0?"Stop "+eur(o.stop):"—",cx+20+(cw-40)*.46,yy+47,10,t.muted,false);
                text(o.active()&&o.buy?eur(gm.reserve(o)):o.active()?o.remaining+" Aktien":"—",cx+20+(cw-40)*.62,yy+26,12,t.text,false);
                text(o.filled()>0?"Ø "+eur(o.average()):"Noch kein Fill",cx+20+(cw-40)*.62,yy+47,10,t.muted,false);
                text(o.status,cx+20+(cw-40)*.81,yy+23,9,o.active()?t.gold:o.status.equals("AUSGEFÜHRT")?t.green:t.muted,true);
                if(o.active())softButton("Stornieren",cx+20+(cw-40)*.81,yy+32,103,25,t.red,()->app.confirm("Order #"+o.id+" stornieren?","Nicht ausgeführte Stücke werden storniert und reserviertes Geld bzw. Aktien freigegeben. Bereits ausgeführte Stücke bleiben bestehen.",()->gm.cancel(o)),"Restorder stornieren");
                else hit(cx+20+(cw-40)*.81,yy+7,160,49,()->app.dialog("Order #"+o.id,o.status+"\n"+o.reason+"\nAusgeführt: "+o.filled()+" Stück zu durchschnittlich "+eur(o.average())+".\nGebühren: "+eur(o.fees),new String[0],new String[0],"Schließen",false),o.reason);
            }
            contentHeight=y+80+list.size()*71-90;
        }
        void fillTable(double y){
            List<Fill> list=game().fills.stream().limit(400).toList();
            if(list.isEmpty()){empty(y,"Jede Ausführung bekommt ihren Platz.","Hier erscheinen Käufe, Verkäufe, Gebühren und realisierte Ergebnisse.",()->navigate("markets"),"Zu den Märkten →");return;}
            card(cx,y,cw,60+list.size()*53);tableLabel("ZEITPUNKT",0,y+27);tableLabel("AKTIE / SEITE",.18,y+27);tableLabel("STÜCK",.40,y+27);tableLabel("AUSFÜHRUNG",.52,y+27);tableLabel("GEBÜHR",.70,y+27);tableLabel("REALISIERT",.86,y+27);
            for(int i=0;i<list.size();i++){
                Fill f=list.get(i);double yy=y+47+i*53;if(i>0)line(cx+18,yy-4,cx+cw-18,yy-4,t.border);
                text(timestamp(f.tick),cx+20,yy+24,11,t.muted,false);text(f.symbol+"  ·  "+(f.buy?"Kauf":"Verkauf"),cx+20+(cw-40)*.18,yy+24,12,f.buy?t.green:t.red,true);
                text(INTEGER.format(f.quantity),cx+20+(cw-40)*.40,yy+24,12,t.text,false);text(eur(f.price),cx+20+(cw-40)*.52,yy+24,12,t.text,true);
                text(eur(f.fee),cx+20+(cw-40)*.70,yy+24,12,t.muted,false);text(f.buy?"—":eur(f.realized),cx+20+(cw-40)*.86,yy+24,12,f.buy?t.faint:sign(f.realized),true);
            }contentHeight=y+80+list.size()*53-90;
        }
        void cashflowTable(double y){
            List<Ledger> list=game().ledger.stream().limit(400).toList();
            if(list.isEmpty()){empty(y,"Noch keine Kontobewegung.","Auch Forschung, Mieten, Zinsen und private Beteiligungen werden hier gebucht.",()->navigate("research"),"Research entdecken →");return;}
            card(cx,y,cw,60+list.size()*53);tableLabel("ZEITPUNKT",0,y+27);tableLabel("BUCHUNG",.18,y+27);tableLabel("BESCHREIBUNG",.42,y+27);tableLabel("BETRAG",.87,y+27);
            for(int i=0;i<list.size();i++){
                Ledger e=list.get(i);double yy=y+47+i*53;if(i>0)line(cx+18,yy-4,cx+cw-18,yy-4,t.border);
                text(timestamp(e.tick),cx+20,yy+24,11,t.muted,false);text(e.kind,cx+20+(cw-40)*.18,yy+24,11,t.text,true);
                text(cut(e.detail,(cw-40)*.41,12,false),cx+20+(cw-40)*.42,yy+24,12,t.muted,false);right(eur(e.amount),cx+cw-20,yy+24,13,sign(e.amount),true);
            }contentHeight=y+80+list.size()*53-90;
        }
        void alertTable(double y){
            List<PriceAlert> list=game().alerts;
            if(list.isEmpty()){empty(y,"Lass den Markt zu dir kommen.","Mit dem Glockensymbol unter dem Chart setzt du Kursalarme.",()->navigate("markets"),"Alarm setzen →");return;}
            card(cx,y,cw,62+list.size()*65);
            tableLabel("WERTPAPIER",0,y+28);tableLabel("ZIELKURS",.30,y+28);tableLabel("RICHTUNG",.52,y+28);tableLabel("STATUS",.72,y+28);
            for(int i=0;i<list.size();i++){
                PriceAlert a=list.get(i);double yy=y+48+i*65;text(a.symbol,cx+20,yy+24,13,t.text,true);text(eur(a.target),cx+20+(cw-40)*.30,yy+24,13,t.text,false);
                text(a.up?"Steigend ↑":"Fallend ↓",cx+20+(cw-40)*.52,yy+24,12,t.muted,false);text(a.active?"Aktiv":"Ausgelöst",cx+20+(cw-40)*.72,yy+24,12,a.active?t.gold:t.green,true);
                softButton("Entfernen",cx+cw-119,yy+5,99,30,t.muted,()->game().alerts.remove(a),"Diesen Kursalarm löschen");
            }contentHeight=y+83+list.size()*65-90;
        }

        // ───────────────────── RESEARCH ───────────────────────────────────

        void research(){
            Game gm=game();pageTitle("Information ist ein Vorteil.","Erforsche Unternehmen. Verbessere dein Werkzeug. Entscheidungen bleiben deine Aufgabe.");
            pill(gm.projects.size()+" / "+gm.researchSlots()+" PROJEKTE",cx+cw-133,119,t.accent);
            double projectY=178;card(cx,projectY,cw,106);
            if(gm.projects.isEmpty()){
                icon("research",cx+23,projectY+27,t.accent,28);text("Dein nächster Wissensvorsprung beginnt hier.",cx+72,projectY+38,17,t.text,true);
                text("Starte eine Unternehmensanalyse oder baue ein permanentes Upgrade aus. Die Forschung läuft mit der Spielzeit.",cx+72,projectY+64,12,t.muted,false);
                text("Freie Forschungsslots: "+gm.researchSlots(),cx+72,projectY+86,10,t.faint,false);
            }else{
                double width=(cw-36)/gm.projects.size();int i=0;
                for(Research p:gm.projects){
                    double xx=cx+18+i*width;double progress=clamp((gm.tick()-p.start)/(double)(p.end-p.start),0,1);
                    text(cut(p.title,width-23,13,true),xx,projectY+29,13,t.text,true);text("Verbleibend: "+String.format(Locale.GERMANY,"%.1f Handelstage",(p.end-gm.tick())/(double)SESSION),xx,projectY+52,10,t.muted,false);
                    rounded(xx,projectY+67,width-25,6,6,t.elevated);rounded(xx,projectY+67,(width-25)*progress,6,6,t.accent);text((int)(progress*100)+" %",xx,projectY+93,10,t.accent,true);i++;
                }
            }
            String[] keys={"routing","lab","private","estate"};String[] details={
                "Reduziert die variable Handelsgebühr um 20 % je Stufe. Die Grundgebühr bleibt bei 0,95 € pro Order.",
                "Ein zusätzlicher paralleler Forschungsplatz und 12 % kürzere neue Projekte je Stufe.",
                "Schaltet größere private Runden frei. Ab Stufe 2: vorzeitiger Sekundärverkauf mit Abschlag.",
                "Senkt laufende Immobilien-Instandhaltungskosten um 15 % je Stufe. Kredite bleiben unverändert."
            };
            double cardW=(cw-48)/4;
            for(int i=0;i<4;i++){
                String key=keys[i];int level=gm.upgrade(key);double x=cx+i*(cardW+16),y=304;
                card(x,y,cardW,218);icon(i==0?"markets":i==1?"research":i==2?"private":"estate",x+19,y+19,t.accent,23);
                right("STUFE "+level+" / 3",x+cardW-18,y+33,9,t.muted,true);text(gm.upgradeName(key),x+19,y+66,17,t.text,true);
                for(int n=0;n<3;n++)rounded(x+19+n*23,y+80,16,4,3,n<level?t.accent:t.border);
                wrap(details[i],x+19,y+110,cardW-38,11,t.muted,3);
                String label=level>=3?"Maximal ausgebaut":gm.researching(key)?"Wird erforscht …":eur(gm.upgradeCost(key))+"  →";
                softButton(label,x+16,y+169,cardW-32,34,level>=3?t.green:t.accent,()->{
                    if(level>=3){app.showToast("Dieses Upgrade ist vollständig erforscht.");return;}
                    if(gm.researching(key)){app.showToast("Dieses Projekt läuft bereits.");return;}
                    app.confirm(gm.upgradeName(key)+" erforschen?",details[Arrays.asList(keys).indexOf(key)]+"\n\nKosten: "+eur(gm.upgradeCost(key))+". Dauer: "+(2+gm.upgrade(key)*2)+" Handelstage vor Laborbonus. Kosten werden sofort abgebucht.",()->gm.startUpgrade(key));
                },details[i]);
            }
            Stock s=stock();double by=543;card(cx,by,cw,223);badge(s,cx+20,by+20,40);text(s.name,cx+75,by+40,20,t.text,true);
            text("ANALYSTEN-DOSSIER  /  "+s.symbol,cx+75,by+61,10,t.muted,true);
            softButton("Unternehmen wechseln",cx+cw-200,by+23,179,32,t.muted,()->app.choose("Unternehmen",gm.stocks.values().stream().map(st->st.symbol+" · "+st.name).toList(),i->select(new ArrayList<>(gm.stocks.values()).get(i))),"Analyse eines anderen Unternehmens ansehen");
            double metricW=(cw-42)/5;
            String[] labels={"KURS","KGV (MODELL)","UMSATZWACHSTUM","DIVIDENDENRENDITE","VERSCHULDUNGSQUOTE"};
            String[] vals={eur(s.price),s.pe()>0?String.format(Locale.GERMANY,"%.1f",s.pe()):"—",plainPct(s.growth),plainPct(s.annualYield),plainPct(s.debtRatio)};
            for(int i=0;i<5;i++){double xx=cx+21+i*metricW;smallCaps(labels[i],xx,by+98);text(vals[i],xx,by+129,22,t.text,true);}
            line(cx+20,by+145,cx+cw-20,by+145,t.border);
            if(s.research==0){
                text("Bewertungsmodell noch gesperrt.",cx+22,by+175,13,t.muted,true);text("Eine Analyse liefert eine unsichere Schätzung statt einer garantierten Kursprognose.",cx+22,by+195,11,t.faint,false);
            }else{
                double lo=s.analystValue*(1-s.analystUncertainty),hi=s.analystValue*(1+s.analystUncertainty);
                text("Geschätzte Bewertung: "+eur(lo)+" – "+eur(hi),cx+22,by+175,16,t.accent,true);
                text("Stufe "+s.research+" · Analyse vom "+date(s.analystDay).format(SHORT_DATE)+" · Nicht garantiert; neue Ereignisse können das Modell überholen.",cx+22,by+196,10,t.muted,false);
            }
            softButton(gm.researching("stock:"+s.symbol)?"Analyse läuft …":(s.research>=3?"Aktualisieren · ":"Erforschen · ")+eur(gm.researchCost(s)),cx+cw-254,by+163,233,36,t.accent,()->startAnalysis(s),"Analyse finanzieren und nach Ablauf der Forschungszeit freischalten");
            double tableY=786;card(cx,tableY,cw,54+gm.stocks.size()*54);
            tableLabel("UNTERNEHMEN",0,tableY+28);tableLabel("SEKTOR",.30,tableY+28);tableLabel("ANALYSETIEFE",.50,tableY+28);tableLabel("NÄCHSTE ZAHLEN",.68,tableY+28);tableLabel("AKTION",.86,tableY+28);
            int i=0;for(Stock st:gm.stocks.values()){
                double yy=tableY+45+i++*54;badge(st,cx+20,yy+9,28);text(st.name,cx+59,yy+27,12,t.text,true);text(SECTORS[st.sector],cx+20+(cw-40)*.30,yy+27,11,t.muted,false);
                for(int n=0;n<3;n++)rounded(cx+20+(cw-40)*.50+n*22,yy+20,15,5,3,n<st.research?t.accent:t.border);
                text(date(st.reportDay).format(SHORT_DATE),cx+20+(cw-40)*.68,yy+27,11,t.muted,false);
                softButton("Dossier öffnen",cx+cw-155,yy+9,133,29,t.muted,()->{select(st);pageScroll=415;},"Kennzahlen und Analystenmodell anzeigen");
            }
            contentHeight=tableY+80+gm.stocks.size()*54-90;
        }
        void startAnalysis(Stock s){
            Game gm=game();if(gm.researching("stock:"+s.symbol)){app.showToast("Diese Analyse läuft bereits.");return;}
            app.confirm("Analyse · "+s.name,"Kosten: "+eur(gm.researchCost(s))+"\nDauer: "+gm.researchDays(s)+" Handelstage vor Laborbonus.\n\n"+
                "Du erhältst eine Schätzung des Unternehmenswerts mit Unsicherheitsband. Höhere Stufen verkleinern die modellierte Unsicherheit. Eine fertige Analyse ist keine sichere Kaufempfehlung und kann später veralten.",()->gm.startResearch(s.symbol));
        }

        // ───────────────────── PRIVATE MARKETS ────────────────────────────

        void privateMarkets(){
            Game gm=game();pageTitle("Nicht alles wird an der Börse gehandelt.","Private Unternehmen. Langer Atem. Chancen, die auch vollständig scheitern können.");
            double sw=(cw-28)/3;
            stat(cx,178,sw,"GEBUNDENE BETEILIGUNGEN",eur(gm.privateValue()),"Geschätzte Buchwerte · nicht sofort auszahlbar",t.gold);
            stat(cx+sw+14,178,sw,"REALISIERTE PRIVATE-ERGEBNISSE",eur(gm.privateProfit),gm.privateExits+" abgeschlossene Exits",sign(gm.privateProfit));
            stat(cx+2*(sw+14),178,sw,"DEIN ZUGANG","Level "+gm.upgrade("private"),"Private Markets im Research-Bereich ausbauen",t.accent);
            double cardW=(cw-32)/3,cardH=335;
            for(int i=0;i<gm.ventures.size();i++){
                Venture v=gm.ventures.get(i);Stake stake=gm.activeStake(v.id);double x=cx+(i%3)*(cardW+16),y=285+(i/3)*(cardH+17);
                card(x,y,cardW,cardH);ventureArt(v,x+1,y+1,cardW-2,86);
                pill(v.stage.toUpperCase(Locale.ROOT),x+18,y+17,new Color(v.color));
                right(stake!=null?"INVESTIERT":gm.upgrade("private")>=v.requirement?"RUNDE OFFEN":"LEVEL "+v.requirement,x+cardW-18,y+32,9,stake!=null?t.green:t.muted,true);
                text(v.name,x+19,y+121,23,t.text,true);text(v.sector.toUpperCase(Locale.GERMAN),x+20,y+143,9,t.faint,true);
                wrap(v.description,x+20,y+168,cardW-40,11,t.muted,3);
                if(stake==null){
                    smallCaps("MINDESTTICKET",x+20,y+235);text(eur(v.ticket),x+20,y+260,23,t.text,true);
                    right(v.duration+" Handelstage",x+cardW-20,y+236,11,t.muted,false);right("Ausfallrisiko "+plainPct(v.failure),x+cardW-20,y+258,10,t.red,false);
                    boolean unlocked=gm.upgrade("private")>=v.requirement;
                    softButton(unlocked?"Investment prüfen  →":"Zugang im Research freischalten",x+17,y+284,cardW-34,35,unlocked?t.accent:t.muted,()->{
                        if(!unlocked){navigate("research");return;}
                        app.confirm("Beteiligung an "+v.name,"Ticket: "+eur(v.ticket)+"\nAnteil vor Verwässerung: "+String.format(Locale.GERMANY,"%.4f %%",100*v.ticket/(v.valuation+v.ticket))+"\nBewertung vor Kapitalzufluss: "+eur(v.valuation)+"\nGeplanter Exit nach "+v.duration+" Handelstagen.\n\nModelliertes Totalverlustrisiko: "+plainPct(v.failure)+". Andere Ausgänge reichen von Teilverlusten bis zu hohen Gewinnen. Erfolgreiche Runden können deinen Anteil verwässern. Buchwerte sind Schätzungen, keine garantierten Verkaufspreise.\n\nVorzeitiger Verkauf erst ab Private Markets Stufe 2, mit 35 % Abschlag und 1 % zusätzlicher Gebühr.",()->gm.invest(v));
                    },unlocked?"Beteiligung und Totalverlustrisiko prüfen":"Private Markets Stufe "+v.requirement+" erforderlich");
                }else{
                    double progress=clamp((gm.day-stake.startDay)/(double)(stake.exitDay-stake.startDay),0,1);
                    text("BUCHWERT",x+20,y+229,9,t.faint,true);text(eur(stake.mark),x+20,y+255,22,t.text,true);
                    right("Noch "+Math.max(0,stake.exitDay-gm.day)+" Handelstage",x+cardW-20,y+250,10,t.muted,false);
                    rounded(x+20,y+268,cardW-40,4,4,t.elevated);rounded(x+20,y+268,(cardW-40)*progress,4,4,t.accent);
                    softButton("Beteiligung verwalten",x+17,y+284,cardW-34,35,t.accent,()->manageStake(stake),"Buchwert, Anteil und vorzeitigen Exit ansehen");
                }
            }
            double end=285+2*(cardH+17);card(cx,end,cw,92);icon("private",cx+20,end+24,t.gold,25);
            text("Bewertung ist nicht Liquidität.",cx+63,end+33,16,t.text,true);text("Private Buchwerte fließen in dein Nettovermögen ein. Sie sagen nicht voraus, welchen Betrag du beim Exit zurückbekommst.",cx+63,end+58,12,t.muted,false);
            contentHeight=end+119-90;
        }
        void ventureArt(Venture v,double x,double y,double w,double h){
            Shape old=g.getClip();g.clip(new RoundRectangle2D.Double(x,y,w,h+15,21,21));Color c=new Color(v.color);
            Paint previous=g.getPaint();g.setPaint(new GradientPaint((float)x,(float)y,alpha(c,12),(float)(x+w),(float)(y+h),alpha(c,38)));g.fill(new Rectangle2D.Double(x,y,w,h));g.setPaint(previous);
            double centerX=x+w*.73,centerY=y+h*.56;
            for(int i=0;i<4;i++){
                g.setColor(alpha(c,25+i*9));g.setStroke(new BasicStroke(1));g.draw(new Ellipse2D.Double(centerX-24-i*14,centerY-24-i*9,48+i*28,48+i*18));
            }
            g.setColor(alpha(c,140));g.fill(new Ellipse2D.Double(centerX-4,centerY-4,8,8));g.setColor(alpha(c,80));g.fill(new Ellipse2D.Double(centerX+39,centerY-29,5,5));
            line(x+14,y+h,x+w-14,y+h,alpha(c,35));g.setClip(old);
        }
        void manageStake(Stake s){
            Game gm=game();String body=s.name+"\nInvestiert: "+eur(s.invested)+"\nGeschätzter Buchwert: "+eur(s.mark)+"\nAnteil: "+String.format(Locale.GERMANY,"%.4f %%",s.ownership*100)+"\nGeplanter Exit: "+date(s.exitDay).format(DATE)+"\n\n";
            if(gm.upgrade("private")<2){app.dialog("Deine Beteiligung",body+"Dein Kapital bleibt bis zum Exit gebunden. Der Sekundärmarkt wird mit Private Markets Stufe 2 verfügbar.",new String[0],new String[0],"Schließen",false);return;}
            double quote=cents(s.mark*.65*.99);app.confirm("Vorzeitigen Exit prüfen",body+"Sekundärmarktangebot: "+eur(quote)+"\nEnthält 35 % Abschlag auf den Buchwert und 1 % zusätzliche Gebühr. Mit Bestätigen verkaufst du die komplette Beteiligung.",()->gm.sellStake(s));
        }

        // ───────────────────── PROPERTY MARKET ────────────────────────────

        void estate(){
            Game gm=game();pageTitle("Baue etwas Bleibendes.","Immobilien mit Mieteinnahmen, Finanzierung, Leerstand und laufenden Kosten.");
            double sw=(cw-42)/4;
            stat(cx,178,sw,"IMMOBILIEN NETTO",eur(gm.propertyValue()),gm.properties.size()+" Objekte im Bestand",t.muted);
            stat(cx+sw+14,178,sw,"RESTSCHULD",eur(gm.totalDebt()),"20-jährige Annuität · fixer Spielzins",t.muted);
            stat(cx+2*(sw+14),178,sw,"ERWARTETE NETTOMIETE",eur(gm.markedAnnualRent()/12),"Monatlich · vor Kreditraten · nicht garantiert",t.green);
            stat(cx+3*(sw+14),178,sw,"MONATLICHE KREDITRATEN",eur(gm.monthlyMortgage()),"Zins und Tilgung · Zahlung alle 21 Handelstage",t.muted);
            double cardW=(cw-32)/3,cardH=371;
            for(int i=0;i<gm.propertyOffers.size();i++){
                PropertyOffer o=gm.propertyOffers.get(i);Property p=gm.ownedProperty(o.id);double x=cx+(i%3)*(cardW+16),y=284+(i/3)*(cardH+18);
                card(x,y,cardW,cardH);propertyArt(o,x+1,y+1,cardW-2,114,i);
                pill(p!=null?"DEIN OBJEKT":"KAUFOBJEKT",x+17,y+17,p!=null?t.green:t.muted);
                text(o.name,x+19,y+144,22,t.text,true);text(o.location,x+19,y+166,11,t.muted,false);
                if(p==null){
                    text(eur(gm.offerPrice(o)),x+19,y+204,28,t.text,true);
                    text("Bruttomietrendite",x+19,y+235,11,t.muted,false);right(plainPct(o.yield)+" p. a.",x+cardW-19,y+235,12,t.green,true);
                    text("Modellierte Belegung",x+19,y+259,11,t.muted,false);right(plainPct(o.occupancy),x+cardW-19,y+259,11,t.text,false);
                    text("Eigenkapital bei 70 % Kredit",x+19,y+284,10,t.muted,false);right(eur(gm.propertyUpfront(o,true)),x+cardW-19,y+284,12,t.text,true);
                    double bw=(cardW-43)/2;
                    softButton("Barkauf",x+17,y+310,bw,35,t.muted,()->reviewProperty(o,false),"Kaufpreis plus 6 % Kaufnebenkosten aus Guthaben zahlen");
                    softButton("Finanzieren →",x+26+bw,y+310,bw,35,t.accent,()->reviewProperty(o,true),"70 % Kredit · 30 % Eigenkapital + 6 % Kaufkosten");
                    text("Fiktives Objekt · 6 % Kaufnebenkosten",x+19,y+361,9,t.faint,false);
                }else{
                    text(eur(p.value),x+19,y+202,27,t.text,true);text("Geschätzter Marktwert",x+19,y+222,10,t.faint,false);
                    text("Restschuld",x+19,y+247,11,t.muted,false);right(eur(p.debt),x+cardW-19,y+247,12,t.text,true);
                    text("Miete nach Betriebskosten bisher",x+19,y+272,10,t.muted,false);right(eur(p.income),x+cardW-19,y+272,11,sign(p.income),false);
                    text("Modernisierung "+p.level+" / 3",x+19,y+295,10,t.faint,false);
                    double bw=(cardW-43)/2;
                    softButton(p.level>=3?"Maximiert":"Modernisieren",x+17,y+311,bw,35,t.accent,()->{
                        if(p.level>=3){app.showToast("Das Objekt ist vollständig modernisiert.");return;}
                        app.confirm("Modernisierung · "+p.name,"Kosten: "+eur(cents(p.value*.05))+"\nMarktwert steigt im Modell um 3,5 %, Mietrendite um 0,4 Prozentpunkte und Belegungswahrscheinlichkeit um 1 Prozentpunkt (maximal 99 %).\n\nDie Investition kann sich erst über Zeit auszahlen.",()->gm.renovate(p));
                    },"Mietpotenzial und Objektqualität verbessern");
                    softButton("Verkaufen",x+26+bw,y+311,bw,35,t.muted,()->app.confirm("Objekt verkaufen?","Verkaufspreis: "+eur(p.value)+"\nVerkaufskosten: 3 %\nKreditablösung: "+eur(p.debt)+"\nNettozufluss: "+eur(cents(p.value*.97-p.debt))+"\n\nMit Bestätigen verlässt das Objekt deinen Bestand.",()->gm.sellProperty(p)),"Verkauf abzüglich Kosten und restlicher Kreditschuld");
                    text("Kreditrate "+eur(p.payment)+" / Monat · Zins "+plainPct(p.rate),x+19,y+361,9,t.faint,false);
                }
            }
            double end=284+2*(cardH+18);card(cx,end,cw,101);text("Liquidität gehört zur Rendite.",cx+21,end+33,17,t.text,true);
            wrap("Mieten und Kreditraten werden alle 21 Handelstage abgerechnet. Leerstand kann die Miete auf null senken; Instandhaltung und Finanzierung laufen weiter. Bei Zahlungslücken werden offene Kauforders freigegeben, Rückstände als Schuld erfasst und 50 € Spielgebühr berechnet.",cx+21,end+58,cw-42,12,t.muted,2);contentHeight=end+124-90;
        }
        void propertyArt(PropertyOffer o,double x,double y,double w,double h,int variant){
            Shape previousClip=g.getClip();g.clip(new RoundRectangle2D.Double(x,y,w,h+15,21,21));Color c=new Color(o.color);
            Paint previous=g.getPaint();g.setPaint(new GradientPaint((float)x,(float)y,alpha(c,t.light?36:10),(float)(x+w),(float)(y+h),alpha(c,t.light?69:28)));g.fill(new Rectangle2D.Double(x,y,w,h));g.setPaint(previous);
            double bx=x+w*.60,base=y+h-8,bw=63+variant*6,bh=55+(variant%3)*15;
            Path2D shadow=new Path2D.Double();shadow.moveTo(bx-15,base);shadow.lineTo(bx+bw+55,base);shadow.lineTo(bx+bw+15,base+14);shadow.lineTo(bx-47,base+14);shadow.closePath();g.setColor(alpha(Color.BLACK,t.light?17:40));g.fill(shadow);
            fill(bx,base-bh,bw,bh,alpha(c,t.light?180:95));
            Path2D side=new Path2D.Double();side.moveTo(bx+bw,base-bh);side.lineTo(bx+bw+22,base-bh-11);side.lineTo(bx+bw+22,base-11);side.lineTo(bx+bw,base);side.closePath();g.setColor(alpha(c,t.light?135:57));g.fill(side);
            Path2D roof=new Path2D.Double();roof.moveTo(bx,base-bh);roof.lineTo(bx+22,base-bh-11);roof.lineTo(bx+bw+22,base-bh-11);roof.lineTo(bx+bw,base-bh);roof.closePath();g.setColor(alpha(c,t.light?225:140));g.fill(roof);
            int rows=Math.max(2,(int)(bh/20)),cols=variant<3?3:4;
            for(int row=0;row<rows;row++)for(int col=0;col<cols;col++){
                double xx=bx+9+col*(bw-12)/cols,yy=base-bh+10+row*17;
                fill(xx,yy,7,9,alpha(t.background,variant%2==0?155:190));line(xx+3,yy+1,xx+3,yy+8,alpha(c,120));
            }
            fill(bx+bw*.46,base-16,11,16,alpha(t.background,155));line(x+17,base+3,x+w-16,base+3,alpha(c,30));g.setClip(previousClip);
        }
        void reviewProperty(PropertyOffer o,boolean mortgage){
            Game gm=game();double price=gm.offerPrice(o),debt=mortgage?price*.7:0,monthly=(gm.rate+.022)/12,payment=debt==0?0:debt*monthly/(1-Math.pow(1+monthly,-240));
            app.confirm((mortgage?"Finanzierter Kauf":"Barkauf")+" · "+o.name,o.description+"\n\nKaufpreis: "+eur(price)+"\nKaufkosten: "+eur(price*.06)+" (6 %)\nSofort fälliges Eigenkapital: "+eur(gm.propertyUpfront(o,mortgage))+"\n"+
                (mortgage?"Kredit: "+eur(debt)+"\nFixer Modellzins: "+plainPct(gm.rate+.022)+"\nMonatliche Annuität: "+eur(payment)+" (20 Jahre)\n":"Keine Kreditfinanzierung.\n")+
                "\nBruttomietrendite: "+plainPct(o.yield)+" p. a.\nBelegungswahrscheinlichkeit: "+plainPct(o.occupancy)+"\nInstandhaltung: 1 % des Marktwerts p. a. vor Forschungsbonus.\n\nMiete und Marktwert sind nicht garantiert. Die Bank und das Objekt existieren nur im Spiel.",()->gm.buyProperty(o,mortgage));
        }

        // ───────────────────── NEWS / CAREER ───────────────────────────────

        void news(){
            Game gm=game();pageTitle("Wissen, was den Markt bewegt.","AUREL Wire · Alle Meldungen werden durch deine simulierte Welt erzeugt.");
            double x=cx;for(String filter:List.of("Alle","Watchlist & Portfolio","Makro","Research")){
                double width=tw(filter,12,true)+35;boolean active=newsFilter.equals(filter);if(active)rounded(x,177,width,34,10,t.elevated);
                center(filter,x+width/2,199,12,active?t.text:t.muted,active);hit(x,177,width,34,()->{newsFilter=filter;pageScroll=0;},"Nachrichten filtern");x+=width+7;
            }
            List<News> list=gm.news.stream().filter(n->newsFilter.equals("Alle")||newsFilter.equals("Makro")&&(n.category.equals("MAKRO")||n.category.equals("KONJUNKTUR"))||newsFilter.equals("Research")&&n.category.equals("RESEARCH")||newsFilter.equals("Watchlist & Portfolio")&&(gm.watchlist.contains(n.symbol)||gm.held(n.symbol)>0)).toList();
            if(list.isEmpty()){empty(231,"Hier ist es gerade ruhig.","Starte die Simulation oder wähle einen anderen Nachrichtenfilter.",()->{newsFilter="Alle";},"Alle Meldungen");return;}
            for(int i=0;i<list.size();i++){
                News n=list.get(i);double y=230+i*166;card(cx,y,cw,150);Color color=n.sentiment>0?t.green:n.sentiment<0?t.red:t.accent;
                rounded(cx+1,y+21,3,38,3,color);pill(n.category,cx+20,y+17,color);right(timestamp(n.tick),cx+cw-21,y+33,10,t.faint,false);
                text(cut(n.title,cw-46,18,true),cx+22,y+71,18,t.text,true);wrap(n.body,cx+22,y+98,cw-47,12,t.muted,2);
                hit(cx+9,y+7,cw-18,136,()->showNews(n),"Meldung vollständig lesen"+(n.symbol.isEmpty()?"":" und Unternehmen öffnen"));
            }contentHeight=230+list.size()*166+10-90;
        }
        void showNews(News n){
            String[] answer=app.dialog(n.category+" · "+timestamp(n.tick),n.title+"\n\n"+n.body+"\n\nQuelle: AUREL-Simulationsengine. Keine echte Unternehmensmeldung.",new String[0],new String[0],n.symbol.isEmpty()?"Schließen":"Unternehmen öffnen",true);
            if(answer!=null&&!n.symbol.isEmpty()&&game().stocks.containsKey(n.symbol)){selected=n.symbol;navigate("markets");}
        }
        void goals(){
            Game gm=game();pageTitle("Vom ersten Trade zum eigenen Imperium.","Meilensteine geben Erfahrungspunkte. Sie erzeugen kein zusätzliches Spielgeld.");
            card(cx,180,cw,125);icon("goals",cx+22,207,t.gold,33);text(gm.rank(),cx+78,222,26,t.text,true);
            text(gm.xp()+" XP  ·  "+gm.achievements.size()+" / "+Game.GOALS.length+" Meilensteine",cx+79,247,12,t.muted,false);
            double progression=clamp(gm.xp()/3850.0,0,1);rounded(cx+79,270,cw-107,6,6,t.elevated);rounded(cx+79,270,(cw-107)*progression,6,6,t.gold);
            double cardW=(cw-32)/3;
            for(int i=0;i<Game.GOALS.length;i++){
                String[] goal=Game.GOALS[i];boolean unlocked=gm.achievements.contains(goal[0]);double x=cx+(i%3)*(cardW+16),y=326+(i/3)*163;
                card(x,y,cardW,146);rounded(x+18,y+19,29,29,9,alpha(unlocked?t.green:t.gold,20));center(unlocked?"✓":String.format(Locale.ROOT,"%02d",i+1),x+32.5,y+39,11,unlocked?t.green:t.gold,true);
                right("+"+goal[3]+" XP",x+cardW-18,y+37,10,unlocked?t.green:t.muted,true);text(goal[1],x+18,y+77,18,t.text,true);
                wrap(goal[2],x+18,y+100,cardW-36,11,t.muted,2);if(unlocked)text("ABGESCHLOSSEN",x+18,y+133,8,t.green,true);
            }contentHeight=326+4*163+20-90;
        }

        // ───────────────────── SETTINGS / MANUAL ──────────────────────────

        void settings(){
            Game gm=game();pageTitle("Dein Workspace.","Lokale Spielstände, Darstellung und volle Kontrolle über deine Karriere.");
            double col=(cw-18)/2;
            card(cx,181,col,219);text("Darstellung",cx+21,216,19,t.text,true);text("Apple-inspiriert. Nativ gezeichnet. Ohne Downloads.",cx+21,241,12,t.muted,false);
            text("Farbschema",cx+21,280,13,t.text,false);button(gm.light?"Hell":"Dunkel",cx+col-129,258,108,34,false,()->gm.light=!gm.light,"Farbschema wechseln");
            text("Bewegung",cx+21,327,13,t.text,false);button(gm.animation?"Aktiv":"Reduziert",cx+col-129,305,108,34,false,()->gm.animation=!gm.animation,"Kurze Hinweisanimationen aktivieren oder reduzieren");
            text("Charts und Simulation bleiben auch ohne Animationen live.",cx+21,375,11,t.faint,false);
            double rx=cx+col+18;card(rx,181,col,219);text("Zeitmaschine",rx+21,216,19,t.text,true);
            wrap("Ein Echtzeit-Sekundenschritt entspricht fünf Börsenminuten bei 1×. Die Börse hat 102 Schritte pro Tag. Wochenenden werden übersprungen.",rx+21,243,col-42,12,t.muted,3);
            softButton("1 Handelstag",rx+20,312,(col-51)/2,35,t.muted,()->app.advanceDays(1),"Bis Handelsschluss simulieren");
            softButton("21 Handelstage",rx+31+(col-51)/2,312,(col-51)/2,35,t.accent,()->app.confirm("21 Handelstage simulieren?","Offene Orders, Forschungen, Beteiligungen, Immobilien und Dividenden laufen weiter. Tagesorders verfallen jeweils bei Handelsschluss. Die Simulation pausiert anschließend.",()->app.advanceDays(21)),"Etwa einen Modellmonat vorspulen");
            text("Forschung braucht Spielzeit, keine offenen Nacht-Sessions.",rx+21,375,11,t.faint,false);
            card(cx,420,cw,197);text("Dein Fortschritt bleibt bei dir.",cx+21,455,20,t.text,true);
            text("Autosave alle 30 Sekunden und beim Schließen. Atomare Speicherung mit Prüfsumme und vorheriger Sicherung.",cx+21,481,12,t.muted,false);
            text(app.savedStatus,cx+21,507,11,t.green,false);
            button("Jetzt speichern",cx+21,529,164,36,true,()->app.save(false),"Strg+S · im Benutzerordner .aurel speichern");
            softButton("Spielstand laden",cx+197,529,161,36,t.muted,app::loadGame,"Eine .aurel-Datei oder .aurel.bak-Sicherung laden");
            softButton("CSV exportieren",cx+370,529,159,36,t.muted,app::exportTrades,"Aktien-Ausführungen exportieren");
            text(cut(SAVE.toString(),cw-42,10,false),cx+21,596,10,t.faint,false);
            card(cx,637,col,196);text("Eine neue Karriere",cx+21,673,20,t.text,true);
            wrap("Wähle dein Startkapital und einen Welt-Seed. Die aktuelle Karriere wird ersetzt. Ein bestehender Save wird als .bak gesichert.",cx+21,701,col-42,12,t.muted,3);
            softButton("Neue Welt erstellen",cx+21,777,col-42,35,t.red,app::newGame,"Neues Spiel mit eigenem Startkapital und Seed");
            card(rx,637,col,196);text("Was dieses Spiel ist.",rx+21,673,20,t.text,true);
            wrap("Eine eigenständige Wirtschaftssimulation. Keine Livekurse, kein Echtgeld und keine externe Verbindung. Steuerrecht, Börsenfeiertage, echtes Kredit-Scoring und vollständige Börsen-Matching-Engines werden nicht abgebildet.",rx+21,700,col-42,12,t.muted,5);
            softButton("Spielanleitung öffnen",rx+21,777,col-42,35,t.muted,app::help,"Vollständige Steuerung und Spielmechanik anzeigen");
            contentHeight=867-90;
        }
        static String helpHtml(boolean light){
            String foreground=light?"#1b2430":"#e9eef5",muted=light?"#596777":"#a8b1bd",bg=light?"#ffffff":"#14181e",accent=light?"#237953":"#a4e8bc";
            return "<html><head><style>body{font-family:sans-serif;font-size:12px;color:"+muted+";background:"+bg+";margin:26px;}h1,h2{color:"+foreground+";}h1{font-size:29px;}h2{font-size:18px;margin-top:25px;}b{color:"+foreground+";}code{color:"+accent+";}</style></head><body>"+"""
                <h1>Willkommen bei AUREL.</h1>
                <p>Baue mit Aktien, Unternehmensforschung, privaten Beteiligungen und Immobilien ein Vermögen auf.
                Alle Firmen und Kurse sind fiktiv. Du spielst ausschließlich mit Spielgeld, ohne Internetverbindung.</p>
                <h2>1. Dein erster Trade</h2>
                <p>Öffne <b>Märkte</b>, wähle links ein Unternehmen und rechts <b>Kaufen</b>. Klicke auf die Stückzahl,
                gib zum Beispiel 10 ein und öffne <b>Kauf prüfen</b>. Erst nach der Bestätigung wird die Order abgegeben.
                Im Portfolio siehst du deinen Bestand und den Einstand inklusive Kaufgebühren.</p>
                <p>Mit <b>Verkaufen</b> schließt du vorhandene Positionen. Du kannst nur Aktien verkaufen, die dir gehören
                und nicht bereits für andere Verkaufsorders reserviert sind. Kredite für den Aktienhandel, Leerverkäufe,
                Optionen und Derivate sind bewusst nicht enthalten.</p>
                <h2>2. Ordertypen richtig nutzen</h2>
                <p><b>Market:</b> Ausführung zum modellierten Geld- oder Briefkurs plus größenabhängigem Preiseffekt.
                Der Bildschirmkurs ist kein garantierter Handelspreis. Große Orders können mehrere Schritte benötigen.</p>
                <p><b>Limit:</b> Kauf höchstens zum Limit, Verkauf mindestens zum Limit. Eine Order bleibt offen,
                wenn der Preis nach Preiseffekt dein Limit verletzt. Es gibt keine garantierte Ausführung.</p>
                <p><b>Stop:</b> Ein Kursziel löst eine Marketorder aus. Ein Verkaufsstop kann Verluste begrenzen,
                garantiert aber keinen bestimmten Verkaufspreis. Kurslücken sind möglich.</p>
                <p><b>Stop-Limit:</b> Nach dem Stop wird eine Limitorder aktiv. Schützt die Preisgrenze,
                kann aber unbefüllt bleiben. <b>Trailing-Stop:</b> Nur für Verkäufe. Der Stop steigt mit dem
                höchsten beobachteten Kurs und fällt bei sinkenden Kursen nicht wieder zurück.</p>
                <p><b>DAY:</b> Restorder verfällt bei Handelsschluss. <b>GTC:</b> Bleibt bis Ausführung oder Stornierung offen.
                Kauforders reservieren Geld, Verkaufsorders Aktien. Market- und Stopkäufe reservieren 4 % Preisaufschlag.
                Bei außergewöhnlichen Kurslücken kann die verfügbare Kaufkraft trotzdem nicht ausreichen.</p>
                <p>Gebühr: <b>0,95 € einmalig je ausgeführter Order plus anfänglich 0,10 % pro ausgeführtem Wert.</b>
                Teilausführungen zahlen die Grundgebühr nicht erneut. Das Routing-Upgrade senkt die variable Gebühr.</p>
                <h2>3. Die Charts bedienen</h2>
                <p>Grüne Kerzen schließen über ihrem Eröffnungskurs, rote darunter. Der Körper zeigt Eröffnung und Schluss;
                der Docht zeigt Hoch und Tief. <b>Mausrad:</b> zoomen. <b>Ziehen:</b> Historie verschieben.
                <b>Doppelklick oder Reset:</b> aktuelle Ansicht wiederherstellen. Der Mauszeiger zeigt OHLC und Zeitpunkt.</p>
                <p><b>1T:</b> heutige 5-Minuten-Kerzen. <b>1W:</b> die letzten fünf Handelstage in 15-Minuten-Kerzen.
                <b>1M / 3M / 1J:</b> Tageskerzen. Zu Spielbeginn existieren rund neun Monate Modellhistorie;
                längere Historie entsteht während des Spielens. Bei allen Ansichten kann der Zoom nur einen Ausschnitt zeigen.</p>
                <p><b>SMA20 / EMA20:</b> gleitender Durchschnitt. <b>BB:</b> 20-Perioden-Bollinger-Bänder mit zwei
                Standardabweichungen. <b>Vol:</b> Volumen. <b>Log:</b> logarithmische Preisachse.
                Goldene Linien markieren durchschnittlichen Einstand oder offene Orderniveaus. Mit der Glocke setzt du Alarme.</p>
                <h2>4. Die simulierte Wirtschaft</h2>
                <p>24 Unternehmen in acht Sektoren teilen sich Markt- und Sektoreinflüsse. Dazu kommen individuelle
                Volatilität, verzögerte Nachrichtenreaktionen, Quartalszahlen, Eröffnungslücken, Dividenden und Zinsentscheidungen.
                Das ist kein reines unabhängiges Würfeln einzelner Kurse, aber auch kein Modell für verlässliche Vorhersagen.</p>
                <p>Der AUREL-24-Index ist ein gleich gewichteter Preisindex, Startwert 1.000; Dividenden werden dort nicht
                reinvestiert. Kontozinsen werden am Tagesende gutgeschrieben. Dividenden reduzieren den Aktienkurs am Ex-Tag.
                Kapitalertragsteuern und Inflation des persönlichen Lebensbudgets werden nicht gebucht.</p>
                <h2>5. Forschung</h2>
                <p>Unternehmensanalysen dauern Spielzeit und kosten Geld. Drei Analysestufen erzeugen immer engere,
                aber unsichere Bewertungsintervalle. Schätzungen können falsch sein und veralten. Ab Stufe 3 kannst du
                das Modell gegen eine Gebühr aktualisieren.</p>
                <p><b>Order-Routing:</b> niedrigere variable Handelskosten. <b>Research-Lab:</b> mehr parallele Plätze und
                kürzere neue Projekte. <b>Private Markets:</b> Zugang zu größeren Runden und Sekundärverkäufen.
                <b>Immobilienmanagement:</b> weniger Instandhaltung. Jedes Upgrade hat drei Stufen.</p>
                <h2>6. Private Equity</h2>
                <p>Ein Ticket kauft einen Anteil an einer privaten Firma. Das Geld bleibt bis zum angegebenen Exit gebunden.
                Buchwerte schwanken und sind keine garantierten Verkaufspreise. Es gibt ausdrücklich ein
                <b>Totalverlustrisiko</b>. Auch nach erfolgreicher Finanzierung kann Verwässerung den Rückfluss reduzieren.</p>
                <p>Private Markets Stufe 2 schaltet vorzeitige Sekundärverkäufe frei: 35 % Abschlag auf den Buchwert,
                anschließend 1 % Gebühr. Der planmäßige Exit wird nach Ablauf der Handelstage am Tagesende abgerechnet.</p>
                <h2>7. Immobilien</h2>
                <p>Barkauf: 100 % Preis plus 6 % Kaufkosten. Finanzierung: 30 % Eigenkapital plus 6 % Kaufkosten;
                70 % werden mit einem festen Spielzins und einer 20-jährigen Annuität finanziert. Das Modell verzichtet
                auf echte Bonitätsprüfungen. Kreditraten enthalten Zinsen und Tilgung.</p>
                <p>Alle 21 Handelstage werden mögliche Mieten, Instandhaltung und Kreditrate abgerechnet.
                Leerstand ist zufällig und kann die Miete eines Monats auf null setzen. Forschung senkt Instandhaltung.
                Modernisierung kostet 5 % des Marktwerts, erhöht Wert und Mietpotenzial. Verkaufskosten: 3 %.</p>
                <p>Bei fehlender Liquidität werden offene Kauforders storniert. Nicht bezahlte Betriebskosten und
                fällige Kreditzinsen werden mit einer Spielgebühr von 50 € zur Schuld addiert. Eine ausbleibende Tilgung
                wird nicht doppelt als neue Schuld gebucht. Es gibt keine reale Bankverbindung oder Nachschussforderung.</p>
                <h2>8. Zeit, Tasten und Spielstände</h2>
                <p><b>Leertaste:</b> Pause / Start. <b>Strg+S</b> (Mac: Cmd+S): speichern.
                <b>Strg+F</b> (Mac: Cmd+F): suchen. <b>1–9:</b> Bereiche wechseln.
                <b>Escape:</b> Suche und Scrollposition zurücksetzen. In Dialogen: Enter bestätigt, Escape bricht ab.</p>
                <p>Bei 1× entspricht eine Sekunde fünf Börsenminuten. 09:00–17:30 ergeben 102 Schritte pro Handelstag.
                Bei 4×, 16× oder 64× läuft die Zeit schneller. +1 Tag springt zum Schluss des aktuellen Tages;
                von einem bereits geschlossenen Tag wird der nächste vollständige Tag simuliert. Einstellungen bieten
                21 Handelstage Vorsprung. Keine echten Feiertage und kein Handel am Wochenende.</p>
                <p>Dein Spielstand liegt im Benutzerordner <code>.aurel/career.aurel</code>. Gespeichert wird automatisch
                alle 30 Sekunden und beim regulären Schließen. <code>.aurel.bak</code> enthält die vorherige Version.
                Nur eigene vertrauenswürdige Spielstände laden. Die Datei ist nicht verschlüsselt und kein Anti-Cheat-System.</p>
                <h2>Was bewusst vereinfacht ist</h2>
                <p>Diskrete Fünf-Minuten-Kurse statt Tickdaten; illustratives Orderbuch statt echter Gegenparteien;
                historische Kurse werden zu Beginn erzeugt; einfache Zins-, Dividenden-, Risiko- und Immobilienmodelle;
                keine Steuern, Splits, Fremdwährungen, Depotüberträge, Multiplayer-Server oder echten Finanzdaten.
                AUREL ist ein Spiel, keine Tradingplattform und keine Anlageberatung.</p>
                </body></html>
                """;
        }
    }

    // ───────────────────── 6. REGRESSION / INVARIANT TESTS ──────────────────
    // Tests exercise the same model used by the UI. No mocking of successful fills.

    static class Tests {
        static int checks;
        static long started;
        static void check(boolean condition,String name){
            checks++;if(!condition)throw new AssertionError("FAILED: "+name);
            System.out.println("  PASS  "+name);
        }
        static void near(double actual,double expected,double tolerance,String name){check(Math.abs(actual-expected)<=tolerance,name+" ["+String.format(Locale.ROOT,"%.4f",actual)+"]");}
        static void rejects(Runnable action,String name){boolean rejected=false;try{action.run();}catch(IllegalArgumentException e){rejected=true;}check(rejected,name);}
        static Game fresh(){return Game.create(137021L,100000);}
        static Stock nova(Game g){return g.stocks.get("NOVA");}
        static void quote(Game g,String sy,double p){Stock s=g.stocks.get(sy);s.price=p;s.liquidity=100000;}
        static void run()throws Exception{
            started=System.currentTimeMillis();System.out.println("AUREL "+VERSION+" — deterministic regression tests\n");
            model();execution();conditions();research();privateAssets();properties();persistence();longRun();
            System.out.println("\nALL "+checks+" CHECKS PASSED in "+(System.currentTimeMillis()-started)+" ms.");
        }
        static void model(){
            Game a=fresh(),b=fresh();check(a.stocks.size()==24,"24 fictional stocks");
            check(a.stocks.values().stream().map(s->s.sector).distinct().count()==8,"eight sectors");
            near(a.wealth(),100000,.001,"initial net worth");near(a.index(),1000,.001,"index baseline");
            check(a.held("NOVA")==0,"no hidden initial holdings");
            for(Stock s:a.stocks.values()){
                boolean good=true;for(Candle c:s.intraday)good&=c.low>0&&c.high>=c.low&&c.high>=c.open&&c.high>=c.close&&c.low<=c.open&&c.low<=c.close&&c.volume>=0;
                for(Candle c:s.daily)good&=c.low>0&&c.high>=c.open&&c.high>=c.close&&c.low<=c.open&&c.low<=c.close;
                check(good,"OHLC consistency: "+s.symbol);
            }
            a.advance(180);b.advance(180);
            check(a.stocks.keySet().stream().allMatch(k->a.stocks.get(k).price==b.stocks.get(k).price),"same seed and actions reproduce all prices");
            check(a.rng.state==b.rng.state,"deterministic random state");
            check(!date(5).getDayOfWeek().equals(DayOfWeek.SATURDAY)&&date(5).getDayOfWeek()==DayOfWeek.MONDAY,"weekends skipped");
            check(timestamp(102).equals("04.01. 17:30"),"close timestamp does not become next-day open");
            check(timestamp(103).equals("05.01. 09:05"),"next-day first candle timestamp");
        }
        static void execution(){
            Game g=fresh();Stock s=nova(g);double cash=g.cash;
            Order buy=g.submit(s.symbol,true,"MARKET",10,0,0,0,"DAY");
            check(buy.status.equals("AUSGEFÜHRT")&&g.held(s.symbol)==10,"market buy fills and adds shares");
            near(g.cash,cash-buy.value-buy.fees,.011,"buy cash accounting");near(g.position(s.symbol).average,(buy.value+buy.fees)/10,.00001,"average cost includes fee");
            check(g.wealth()<cash,"spread and commission reduce immediate wealth");
            s.liquidity=100000;Order sell=g.submit(s.symbol,false,"MARKET",10,0,0,0,"DAY");
            check(g.held(s.symbol)==0&&g.position(s.symbol).average==0,"full sale clears average and holdings");
            near(g.realized,g.cash-100000,.02,"round-trip realized result reconciles with cash");check(g.realized<0,"same-quote round trip cannot print money");
            rejects(()->g.submit(s.symbol,false,"MARKET",1,0,0,0,"DAY"),"short selling rejected");
            rejects(()->g.submit(s.symbol,true,"MARKET",0,0,0,0,"DAY"),"zero quantity rejected");
            rejects(()->g.submit(s.symbol,true,"MARKET",10000000,0,0,0,"DAY"),"unfunded order rejected");
            rejects(()->g.submit(s.symbol,true,"LIMIT",1,Double.NaN,0,0,"DAY"),"NaN price rejected");
            rejects(()->g.submit(s.symbol,true,"BOGUS",1,0,0,0,"DAY"),"unknown order type rejected");
            rejects(()->g.submit(s.symbol,true,"TRAILING",1,0,0,.03,"DAY"),"buy trailing-stop not offered");
            Game r=fresh();Stock rs=nova(r);double limit=rs.price*.5;
            Order dormant=r.submit(rs.symbol,true,"LIMIT",10,limit,0,0,"GTC");
            near(r.cash,100000,0,"unfilled order does not debit cash");check(r.reservedCash()>0&&r.freeCash()<r.cash,"pending buy reserves cash");
            r.cancel(dormant);near(r.freeCash(),r.cash,0,"cancellation releases cash");
            Game partial=fresh();Stock ps=nova(partial);ps.liquidity=3;
            Order o=partial.submit(ps.symbol,true,"MARKET",8,0,0,0,"GTC");check(o.filled()==3&&o.remaining==5&&o.status.equals("TEILWEISE"),"finite liquidity causes partial fill");
            ps.liquidity=3;partial.processOrders();check(o.filled()==6&&o.remaining==2,"second partial fill preserves remainder");
            ps.liquidity=3;partial.processOrders();check(o.status.equals("AUSGEFÜHRT")&&partial.held(ps.symbol)==8,"partial fills complete without overfill");
            double variable=partial.fills.stream().mapToDouble(f->cents(f.price*f.quantity*partial.feeRate())).sum();near(o.fees,variable+.95,.011,"base commission charged only once per order");
            Game priority=fresh();Stock pr=nova(priority);pr.liquidity=0;
            Order older=priority.submit(pr.symbol,true,"LIMIT",5,pr.price*1.1,0,0,"GTC");Order newer=priority.submit(pr.symbol,true,"LIMIT",5,pr.price*1.1,0,0,"GTC");
            pr.liquidity=3;priority.processOrders();check(older.filled()==3&&newer.filled()==0,"earlier order consumes available liquidity first");
            Game held=fresh();Stock hs=nova(held);held.submit(hs.symbol,true,"MARKET",10,0,0,0,"DAY");
            held.submit(hs.symbol,false,"LIMIT",8,hs.price*2,0,0,"GTC");check(held.availableShares(hs.symbol)==2,"pending sell reserves shares");
            rejects(()->held.submit(hs.symbol,false,"LIMIT",3,hs.price*2,0,0,"GTC"),"overlapping sell reservations rejected");
        }
        static void conditions(){
            Game g=fresh();Stock s=nova(g);quote(g,s.symbol,100);
            Order limit=g.submit(s.symbol,true,"LIMIT",5,90,0,0,"GTC");check(limit.filled()==0,"buy limit above current ask condition not met");
            s.last().low=1;s.last().high=10000;g.processOrders();check(limit.filled()==0,"past candle extrema cannot retroactively fill new order");
            quote(g,s.symbol,85);g.processOrders();check(limit.filled()==5&&limit.average()<=90,"buy limit fills only at limit or better");
            Order sell=g.submit(s.symbol,false,"LIMIT",5,95,0,0,"GTC");check(sell.filled()==0,"sell limit waits for adequate bid");quote(g,s.symbol,100);g.processOrders();check(sell.filled()==5&&sell.average()>=95,"sell limit never executes below limit");
            Game stopGame=fresh();Stock st=nova(stopGame);quote(stopGame,st.symbol,100);stopGame.submit(st.symbol,true,"MARKET",10,0,0,0,"DAY");
            Order stop=stopGame.submit(st.symbol,false,"STOP",5,0,95,0,"GTC");check(!stop.activated,"sell stop initially dormant");quote(stopGame,st.symbol,80);stopGame.processOrders();check(stop.activated&&stop.filled()==5&&stop.average()<95,"gapped stop becomes market, not guaranteed stop price");
            quote(stopGame,st.symbol,100);Order stopLimit=stopGame.submit(st.symbol,false,"STOP-LIMIT",5,94,95,0,"GTC");quote(stopGame,st.symbol,80);stopGame.processOrders();check(stopLimit.activated&&stopLimit.filled()==0,"stop-limit may remain unfilled after gap");
            quote(stopGame,st.symbol,96);stopGame.processOrders();check(stopLimit.filled()==5&&stopLimit.average()>=94,"triggered stop-limit fills on acceptable recovery");
            Game trail=fresh();Stock ts=nova(trail);quote(trail,ts.symbol,100);trail.submit(ts.symbol,true,"MARKET",5,0,0,0,"DAY");Order trailing=trail.submit(ts.symbol,false,"TRAILING",5,0,0,.1,"GTC");
            near(trailing.stop,90,0,"initial trailing stop");quote(trail,ts.symbol,110);trail.processOrders();near(trailing.stop,99,0,"trailing stop rises with peak");quote(trail,ts.symbol,108);trail.processOrders();near(trailing.stop,99,0,"trailing stop never follows price downward");quote(trail,ts.symbol,95);trail.processOrders();check(trailing.filled()==5&&trailing.average()<99,"trailing stop executes through gap");
            Game expiry=fresh();Stock es=nova(expiry);
            Order day=expiry.submit(es.symbol,true,"LIMIT",1,es.price*.2,0,0,"DAY");Order gtc=expiry.submit(es.symbol,true,"LIMIT",1,es.price*.2,0,0,"GTC");
            expiry.advance(SESSION-expiry.slot);check(day.status.equals("ABGELAUFEN")&&gtc.active(),"DAY expires, GTC survives close");
            rejects(()->expiry.submit(es.symbol,true,"MARKET",1,0,0,0,"DAY"),"orders rejected while exchange closed");check(expiry.interest>0,"positive cash interest credited at close");
            Game alerts=fresh();Stock as=nova(alerts);alerts.addAlert(as.symbol,as.price+1);quote(alerts,as.symbol,as.price+2);alerts.checkAlerts(as,as.price-2);check(!alerts.alerts.get(0).active,"price alert triggers and deactivates");
            Game dividend=fresh();Stock div=dividend.stocks.get("NRGY");dividend.submit(div.symbol,true,"MARKET",10,0,0,0,"DAY");int offset=Math.abs(div.symbol.hashCode())%63;if(offset==0)offset=63;dividend.day=offset-1;dividend.slot=SESSION;dividend.step();check(dividend.dividends>0,"quarterly dividend credits eligible holding");
            Game reports=fresh();Stock r=nova(reports);r.reportDay=1;double eps=r.eps;reports.advance(SESSION-reports.slot+1);check(r.reportDay==64&&r.eps!=eps&&reports.news.stream().anyMatch(n->n.category.equals("QUARTALSZAHLEN")),"earnings update fundamentals, schedule and news");
        }
        static void research(){
            Game g=fresh();Stock s=nova(g);double cost=g.researchCost(s);g.startResearch(s.symbol);
            near(g.cash,100000-cost,.001,"research cost charged immediately");check(s.research==0,"research not immediately unlocked");
            rejects(()->g.startResearch(s.symbol),"duplicate research rejected");rejects(()->g.startUpgrade("routing"),"research slot limit enforced");
            g.advance(SESSION-1);check(s.research==0,"research waits full duration");g.step();check(s.research==1&&s.analystValue>0&&s.analystDay>=0,"analysis completes with dated uncertain estimate");
            check(g.projects.isEmpty()&&g.researchCompleted==1,"completed project releases slot");
            g.upgrades.put("lab",2);g.startResearch("VELA");g.startResearch("ORBT");g.startUpgrade("routing");check(g.projects.size()==3,"lab grants parallel projects");
            Research p=g.projects.get(0);check(p.end-p.start==(int)Math.ceil(SESSION*.76),"lab shortens newly started projects");
            g.advance(SESSION*2);check(g.upgrade("routing")==1,"permanent upgrade takes effect after completion");near(g.feeRate(),.0008,1e-12,"routing lowers variable fee");
            g.upgrades.put("routing",3);rejects(()->g.startUpgrade("routing"),"upgrade level cap enforced");near(g.feeRate(),.0004,1e-12,"maximum routing discount");
        }
        static void privateAssets(){
            Game g=fresh();Venture v=g.ventures.get(0);g.invest(v);Stake s=g.activeStake(v.id);
            near(g.cash,100000-v.ticket,.001,"private ticket debits cash");near(g.wealth(),100000,.001,"initial private mark is included once in wealth");near(s.ownership,v.ticket/(v.valuation+v.ticket),1e-12,"post-money ownership calculated");
            rejects(()->g.invest(v),"duplicate private round rejected");rejects(()->g.sellStake(s),"secondary exit locked before level two");
            rejects(()->g.invest(g.ventures.get(5)),"advanced private access locked");
            g.upgrades.put("private",2);double before=g.cash,expected=cents(s.mark*.65*.99);g.sellStake(s);near(g.cash,before+expected,.001,"secondary sale applies discount and fee");check(!s.active()&&g.privateValue()==0,"secondary sale removes active mark");
            Game fail=fresh();fail.invest(fail.ventures.get(0));Stake f=fail.stakes.get(0);f.failure=1;fail.day=f.exitDay;fail.updatePrivate();check(f.status.equals("AUSGEFALLEN")&&f.payout==0,"venture can lose entire principal");near(fail.privateProfit,-f.invested,.001,"venture total loss reconciles");
            Game win=fresh();win.invest(win.ventures.get(0));Stake w=win.stakes.get(0);w.failure=0;win.day=w.exitDay;double old=win.cash;win.updatePrivate();check(w.payout>0&&!w.active(),"non-failure private exit releases capital");near(win.cash,old+w.payout,.001,"private payout matches cash credit");
        }
        static void properties(){
            Game g=fresh();PropertyOffer offer=g.propertyOffers.get(0);double cost=g.propertyUpfront(offer,true);g.buyProperty(offer,true);Property p=g.properties.get(0);
            near(g.cash,100000-cost,.001,"mortgage purchase debits equity and purchase costs");near(p.debt,offer.price*.7,.001,"70 percent loan principal");near(g.wealth(),100000-offer.price*.06,.001,"net property equity excludes loan liability");
            rejects(()->g.buyProperty(offer,true),"same property cannot be purchased twice");
            double value=p.value,before=g.cash;g.renovate(p);near(g.cash,before-value*.05,.011,"renovation cash expense");near(p.value,value*1.035,.011,"renovation appraisal rises less than cost");check(p.level==1&&p.rentYield>offer.yield,"renovation improves property attributes");
            double net=cents(p.value*.97-p.debt);before=g.cash;g.sellProperty(p);near(g.cash,before+net,.011,"property sale deducts sales fee and pays loan");check(g.properties.isEmpty()&&g.totalDebt()==0,"sale removes property and loan together");
            Game mortgage=fresh();mortgage.buyProperty(mortgage.propertyOffers.get(0),true);Property mp=mortgage.properties.get(0);mp.occupancy=1;double debt=mp.debt;
            mortgage.day=19;mortgage.updateProperties();near(mp.debt,debt,0,"no premature monthly payment");mortgage.day=20;mortgage.updateProperties();check(mp.debt<debt&&mp.income>0,"monthly rent paid and principal amortized");
            Game shortage=fresh();shortage.buyProperty(shortage.propertyOffers.get(0),true);Property sp=shortage.properties.get(0);sp.occupancy=0;shortage.cash=0;shortage.day=20;double oldDebt=sp.debt;shortage.updateProperties();
            double maintenance=cents(sp.value*.01/12),interest=cents(oldDebt*sp.rate/12);near(sp.debt,oldDebt+maintenance+interest+50,.011,"unpaid interest and costs capitalized without double-counting principal");near(shortage.cash,0,0,"payment shortage cannot create negative cash");
            Game cashBuyer=fresh();cashBuyer.buyProperty(cashBuyer.propertyOffers.get(0),false);near(cashBuyer.totalDebt(),0,0,"cash purchase has no mortgage");
            Game unfunded=Game.create(2,1000);rejects(()->unfunded.buyProperty(unfunded.propertyOffers.get(0),true),"unfunded down payment rejected");
        }
        static void persistence()throws Exception{
            Game g=fresh();g.submit("NOVA",true,"MARKET",12,0,0,0,"DAY");g.startResearch("NOVA");g.invest(g.ventures.get(0));g.buyProperty(g.propertyOffers.get(0),true);g.addAlert("NOVA",nova(g).price*1.1);g.advance(75);
            Path directory=Files.createTempDirectory("aurel-tests-");Path path=directory.resolve("test.aurel");
            Storage.write(path,Storage.encode(g));Game loaded=Storage.load(path);near(loaded.wealth(),g.wealth(),1e-7,"save restores total wealth");
            check(loaded.held("NOVA")==g.held("NOVA")&&loaded.stakes.size()==1&&loaded.properties.size()==1&&loaded.alerts.size()==1,"save restores positions, private assets, mortgage and alerts");
            check(loaded.projects.size()==g.projects.size()&&loaded.rng.state==g.rng.state,"save restores research and complete RNG state");
            g.advance(101);loaded.advance(101);check(g.stocks.keySet().stream().allMatch(k->g.stocks.get(k).price==loaded.stocks.get(k).price),"loaded game continues same future price path");near(loaded.cash,g.cash,1e-7,"loaded game continues identical cash ledger");
            Storage.write(path,Storage.encode(g));check(Files.exists(directory.resolve("test.aurel.bak")),"atomic write keeps previous backup");
            byte[] broken=Files.readAllBytes(path);broken[broken.length-20]^=7;Path corrupt=directory.resolve("bad.aurel");Files.write(corrupt,broken);boolean rejected=false;try{Storage.load(corrupt);}catch(IOException e){rejected=true;}check(rejected,"corrupt save checksum rejected");
            Path csv=directory.resolve("trades.csv");Storage.export(g,csv);String text=Files.readString(csv);check(text.startsWith("\uFEFFDatum;")&&text.contains(";NOVA;KAUF;12;"),"CSV export contains readable recorded fills");
            for(Path p:Files.list(directory).toList())Files.delete(p);Files.delete(directory);
            near(Window.parseNumber("1.250,75 €"),1250.75,0,"German formatted decimal input");near(Window.parseNumber("1250.75"),1250.75,0,"plain dot decimal input");
            rejects(()->Window.parseNumber("Infinity"),"nonfinite numeric input rejected");
        }
        static void longRun(){
            Game g=fresh();g.startResearch("NOVA");g.buyProperty(g.propertyOffers.get(0),true);g.invest(g.ventures.get(0));
            ArrayList<Stock> stocks=new ArrayList<>(g.stocks.values());
            for(int i=0;i<SESSION*85;i++){
                if(g.slot<SESSION&&i%270==0){Stock s=stocks.get((i/270)%stocks.size());if(g.maxBuy(s.symbol)>4)g.submit(s.symbol,true,"MARKET",3,0,0,0,"GTC");}
                if(g.slot<SESSION&&i%451==0){Stock s=stocks.get((i/451)%stocks.size());if(g.availableShares(s.symbol)>0)g.submit(s.symbol,false,"MARKET",1,0,0,0,"DAY");}
                g.step();
                if(!Double.isFinite(g.cash)||g.cash<0||!Double.isFinite(g.wealth()))throw new AssertionError("invalid account during stress run");
                for(Stock s:stocks){if(!Double.isFinite(s.price)||s.price<.10||s.liquidity<0||g.availableShares(s.symbol)<0)throw new AssertionError("invalid market during stress run");}
            }
            check(g.day>=85,"85-session mixed-asset stress run completes");
            check(g.stocks.values().stream().allMatch(s->s.intraday.size()<=MAX_INTRADAY&&s.daily.size()<=521),"historical memory is bounded");
            check(g.news.size()<=180&&g.ledger.size()<=3000&&g.equity.size()<=6500,"news, ledger and equity histories are bounded");
            check(g.researchCompleted>0&&g.privateExits>0&&g.properties.get(0).debt>0,"long-run research, private exit and mortgage remain functional");
            check(g.achievements.contains("first")&&g.achievements.contains("research")&&g.achievements.contains("estate"),"career milestones respond to actual actions");
        }
    }
}
