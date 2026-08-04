#!/usr/bin/env perl

use strict;
use warnings;
use utf8;
use open qw(:std :encoding(UTF-8));

my ($input_path, $output_path) = @ARGV;
die "Usage: $0 emoji-test.txt output.kt\n" unless $input_path && $output_path;

my @category_order = (
    'smileys_people',
    'animals_nature',
    'food_drink',
    'activities',
    'travel_places',
    'objects',
    'symbols',
    'flags',
);

my %category_for_group = (
    'Smileys & Emotion' => 'smileys_people',
    'People & Body'     => 'smileys_people',
    'Component'         => 'smileys_people',
    'Animals & Nature'  => 'animals_nature',
    'Food & Drink'      => 'food_drink',
    'Activities'        => 'activities',
    'Travel & Places'   => 'travel_places',
    'Objects'           => 'objects',
    'Symbols'           => 'symbols',
    'Flags'             => 'flags',
);

my %category_metadata = (
    smileys_people => ['スマイリーと人々', '😀'],
    animals_nature => ['動物と自然', '🐻'],
    food_drink     => ['食べ物と飲み物', '🍴'],
    activities     => ['アクティビティ', '⚽'],
    travel_places  => ['旅行と場所', '🚗'],
    objects        => ['もの', '💡'],
    symbols        => ['記号', '💯'],
    flags          => ['旗', '🏳️'],
);

open my $input, '<:encoding(UTF-8)', $input_path or die "Cannot read $input_path: $!\n";
my (%emojis, %seen);
my $category;
while (my $line = <$input>) {
    if ($line =~ /^# group: (.+)$/) {
        my $group = $1;
        $group =~ s/\s+$//;
        $category = $category_for_group{$group};
        next;
    }
    next unless defined $category;
    next unless $line =~ /^([0-9A-F ]+)\s*;\s*(fully-qualified|component)\s*#/;
    my @code_points = map { hex($_) } split /\s+/, $1;
    # Show one default variant in the picker instead of listing every skin tone.
    next if grep { $_ >= 0x1F3FB && $_ <= 0x1F3FF } @code_points;
    my $emoji = pack('U*', @code_points);
    next if $seen{$emoji}++;
    push @{$emojis{$category}}, $emoji;
}
close $input;

open my $output, '>:encoding(UTF-8)', $output_path or die "Cannot write $output_path: $!\n";
print {$output} <<'HEADER';
package com.nostr.torinos.ui.components

// Generated from https://www.unicode.org/Public/17.0.0/emoji/emoji-test.txt by
// scripts/generate_standard_emoji_catalog.pl. Do not edit manually.
internal const val STANDARD_EMOJI_VERSION = "17.0"

internal data class StandardEmojiCategory(
    val label: String,
    val icon: String,
    val emojis: List<String>,
)

private fun emojiList(vararg chunks: String): List<String> =
    chunks.flatMap { chunk -> chunk.lineSequence().filter(String::isNotEmpty).toList() }

internal val STANDARD_EMOJI_CATEGORIES: List<StandardEmojiCategory> = listOf(
HEADER

my $chunk_size = 250;
for my $category_key (@category_order) {
    my ($label, $icon) = @{$category_metadata{$category_key}};
    my @values = @{$emojis{$category_key} // []};
    print {$output} "    StandardEmojiCategory(\n";
    print {$output} "        label = \"$label\",\n";
    print {$output} "        icon = \"$icon\",\n";
    print {$output} "        emojis = emojiList(\n";
    while (@values) {
        my @chunk = splice @values, 0, $chunk_size;
        print {$output} "            \"\"\"\n", join("\n", @chunk), "\n\"\"\".trimIndent(),\n";
    }
    print {$output} "        ),\n";
    print {$output} "    ),\n";
}
print {$output} ")\n";
close $output;

my $total = 0;
$total += scalar @{$emojis{$_} // []} for @category_order;
print "Generated $total emoji sequences in $output_path\n";
