/*
 * Copyright (C) 2026 The DiamaneOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.widget;

import java.util.Random;

/**
 * Short-list passwords: the first three letters of six words of EFF's short word list 2, 18
 * lowercase letters with nothing between them. No two words of the list start with the same
 * three letters, so the letters stand for the words. LockSettingsService generates them, and
 * Settings shows the whole words to help remember the letters.
 *
 * <p>The words are the 1,295 of the list made of a to z, all but yo-yo: 62 bits for six drawn
 * at random. The list is by the Electronic Frontier Foundation, under CC BY 3.0 US:
 * https://www.eff.org/files/2016/09/08/eff_short_wordlist_2_0.txt
 *
 * @hide
 */
public final class ShortListPasswords {

    /** Number of words. */
    public static final int WORDS = 6;

    /** Letters of each word that are typed. */
    public static final int PREFIX_LENGTH = 3;

    /** Number of letters of a short-list password. */
    public static final int LENGTH = WORDS * PREFIX_LENGTH;

    // The first three letters of each word, in the order of the list, which is sorted.
    private static final String PREFIXES =
            "aarabaabbabdabhabiabnabrabsabuabyacaaccaceachaciacoacqacractacuaeraesaffafl"
            + "afraftagaageaggaghagiagnagoagraidaimajaalaalbalcalfalgalialkalmaloalpalrals"
            + "altalualwamaambameamiammamnamoampamuanaancandaneanganiankannanoansantanxany"
            + "aorapaapnapoappapraquaraarbardareargariarmaroarrarsartasbascaseashasiaslaso"
            + "aspastasyatlatmatoatrattatyaucaudaugausautauxavaaveaviavoawaawhawkawnawoaxi"
            + "azababbacbadbagbakbalbambanbarbasbatbazblablebliblobluboabobbodbogbohboibon"
            + "booborbosbotbouboxbrebribrobrububbucbudbufbulbunbusbuzcabcaccadcafcagcahcaj"
            + "cakcalcamcancapcarcascatcaucavceacedcelcemcencerceschachechichlchochrchucil"
            + "cincircitcivclacleclicloclucoacobcoccodcoecofcogcohcolcomconcopcorcoscotcou"
            + "covcoycozcracrecricrocrucrycubcuccudcufcuiculcupcurcuscutcybcyccylcymcyncyp"
            + "cytdacdafdagdaidaldandardasdatdaudawdaydazdeadebdecdeddeedefdegdehdeldemden"
            + "deodepderdesdetdevdexdiadibdicdifdigdildimdindiodipdirdisditdivdizdocdoddol"
            + "domdondoodordoudowdozdradredridrodrudrydubducdufdugdumdupdurdusdutduvdwadwe"
            + "dwidyndyseageareaseaveboeccecheclecoecsedgediedueeleereffeggegoejeelaelbeld"
            + "eleelfelielkelleloelseluelvemaembemcemeemiemoempemuenaencendeneenfengenheni"
            + "enjenlenoenqenrensentenuenvenzepiequeraergerreruesceskesoespessestetceteeth"
            + "etieuceuleupeutevaeveevievoexaexcexeexfexhexiexoexpexqextexufabfacfadfaifal"
            + "famfanfasfaufavfeafebfedfeefeifelfemfenferfesfetfeufevfibficfidfigfilfinfis"
            + "fixflafleflifloflyfoafocfogfolfonfoofosfoufoxfrafrefrifrofrufrygadgaigalgam"
            + "gangargasgatgaugeagecgemgengeogergesgetgeyghogibgidgifgiggimgirgivgizglagle"
            + "gliglogluglygnagnogoagoggolgongoogorgosgotgougovgragregrigrogruguagueguigul"
            + "gumgurgusgutgymgyngyrhabhachaghaihalhamhanhapharhashathauhazheahedhefheihel"
            + "hemhenherheshexhubhudhufhughulhumhunhurhushyahybhydhyghypibuiceiciicoideidi"
            + "idligligniguillimaimbimiimmimpiodionipaiphiriirkiroirrislisoissitaiteitiitu"
            + "ivojabjacjagjaijaljamjanjarjasjaujawjayjazjeajeejeljeojerjetjezjifjigjinjob"
            + "jocjogjohjoijokjoujovjoyjubjudjugjuijujjukjumjunjurjusjuvkabkamkankarkaykee"
            + "kenkerketkhakickilkimkinkiokiskitkleknakneknikoakrylabladlaklanlaplarlaslat"
            + "laulavlaxlazlecleflegleilemlenleolepletleulevlewlialibliclifligliklillimlin"
            + "liolipliqlislitlivlizllalualublucludlugluklullumlunlurlusluxlyrmacmaemagmah"
            + "maimajmakmalmammanmapmarmasmatmavmaxmaymoamobmocmodmoimolmommonmoomormosmot"
            + "moumovmowmozmucmudmugmulmummunmupmurmusmutmyrmysmytnainamnannapnarnasnatnau"
            + "navneanebnecnefnegneinemneonepnernesnetneunevnexnicnienimninnirnucnugnuinul"
            + "numnupnurnutnyloasoatobeobiobjoblobnobsobtobvoccoceoctocuoffoftoiloinoldoly"
            + "omiomnonconionlonsonwonyoomopaopeopiopooppoptopuoscosmostothougoutovaoveowl"
            + "oxfoxioxyoysozopacpadpagpajpalpampanpapparpaspatpaupavpaypebpecpedpegpelpen"
            + "peopepperpespetpewphaphephophrphyplapleplopluplypnepodpoepogpoipokpolponpop"
            + "porpospoupowpraprepripropruprypsepsypubpucpuepulpumpunpuppurpusputpuzpyrpyt"
            + "quaquequiquoracradragrairamranrarrasravrearebrecreerefregrehreirejrekrelrem"
            + "renreorepreqrerresretreurevrewrharherhirhurhyribricridrigrimriprisritrivroa"
            + "robrocromroprotrouroyrubrudrugruirulrumrunruprussabsacsadsafsaisalsamsansap"
            + "sarsassatsausavsaxscasceschsciscoscrscuscysecsedsegseiselsemsensepseqserses"
            + "setsevshasheshishoshrshushysiasibsiesilsimsinsissitsixsizskaskeskiskuskysla"
            + "sleslisloslusmasmesmismosmusnasnesnisnosnuspesphspisplsposprspuspysqustaste"
            + "stistostrstustysuasubsucsudsufsugsuisulsupsursusswasweswiswosycsylsymsynsyr"
            + "systabtactadtaetagtaktaltamtantaptartastattavthathethithothrthuthwtiatidtie"
            + "tigtimtintiptirtistratretritrotrutrytubtuetugtultumtupturtustuttuxtwetwityr"
            + "ultumbumpunaunbuncunduneunfungunhuniunjunkunlunmunnunounpunqunrunsuntunuunv"
            + "unwunyunzupbupcupdupfupguphupkupluppuprupsuptupwuraurburcureurgurouseushute"
            + "utiutmutouttvacvagvalvanvapvarvasvegvehvelvenvervesvetvexvicvidvievigvilvin"
            + "viovipvirvisvitvivvixvocvogvoivolvouvoyvulwafwagwakwalwanwaswatwavwhewhiwho"
            + "wicwidwiewifwikwilwinwipwirwiswizwobwolwomwooworwouwrawrewriwroxerxylyacyah"
            + "yaryeayesyidyieyodyogyupzeazebzepzeszigzilzipzirzodzomzoozuc";

    /** Number of words a password is drawn from. */
    public static final int PREFIX_COUNT = PREFIXES.length() / PREFIX_LENGTH;

    private ShortListPasswords() {
    }

    /** Whether the password has the shape of a short-list password. */
    public static boolean isShortListPassword(byte[] password) {
        if (password.length != LENGTH) {
            return false;
        }
        for (int i = 0; i < password.length; i += PREFIX_LENGTH) {
            if (!isPrefix(password, i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Fills {@code letters} with a random short-list password: six words drawn independently
     * and uniformly. A draw with fewer than {@code minDistinctLetters} different letters is
     * drawn again whole, so that every password that is returned is equally likely.
     *
     * @param letters {@link #LENGTH} long
     */
    public static void generate(Random random, int minDistinctLetters, char[] letters) {
        if (letters.length != LENGTH) {
            throw new IllegalArgumentException("letters must be " + LENGTH + " long");
        }
        do {
            for (int i = 0; i < WORDS; i++) {
                // nextInt(bound) draws again rather than reduce, so it has no modulo bias.
                final int word = random.nextInt(PREFIX_COUNT) * PREFIX_LENGTH;
                PREFIXES.getChars(word, word + PREFIX_LENGTH, letters, i * PREFIX_LENGTH);
            }
        } while (distinctLetters(letters) < minDistinctLetters);
    }

    private static int distinctLetters(char[] letters) {
        int seen = 0;
        for (char c : letters) {
            seen |= 1 << (c - 'a');
        }
        return Integer.bitCount(seen);
    }

    // A binary search of PREFIXES for the three bytes at the offset.
    private static boolean isPrefix(byte[] password, int offset) {
        int low = 0;
        int high = PREFIX_COUNT - 1;
        while (low <= high) {
            final int middle = (low + high) >>> 1;
            int order = 0;
            for (int i = 0; i < PREFIX_LENGTH && order == 0; i++) {
                order = (password[offset + i] & 0xff)
                        - PREFIXES.charAt(middle * PREFIX_LENGTH + i);
            }
            if (order == 0) {
                return true;
            } else if (order < 0) {
                high = middle - 1;
            } else {
                low = middle + 1;
            }
        }
        return false;
    }
}
